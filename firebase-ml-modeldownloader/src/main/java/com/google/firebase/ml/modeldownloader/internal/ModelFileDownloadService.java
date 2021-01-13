// Copyright 2020 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.ml.modeldownloader.internal;

import android.app.DownloadManager;
import android.app.DownloadManager.Query;
import android.app.DownloadManager.Request;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.util.LongSparseArray;
import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import com.google.android.datatransport.TransportFactory;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.ml.modeldownloader.CustomModel;
import com.google.firebase.ml.modeldownloader.CustomModelDownloadConditions;
import com.google.firebase.ml.modeldownloader.FirebaseMlException;
import com.google.firebase.ml.modeldownloader.internal.FirebaseMlLogEvent.ModelDownloadLogEvent.DownloadStatus;
import com.google.firebase.ml.modeldownloader.internal.FirebaseMlLogEvent.ModelDownloadLogEvent.ErrorCode;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Calls the Android Download service to copy the model file to device (temp location) and then
 * moves file to it's permanent location, updating the model details in shared preferences
 * throughout.
 *
 * @hide
 */
public class ModelFileDownloadService {

  private static final String TAG = "ModelFileDownloadSer";
  private static final int COMPLETION_BUFFER_IN_MS = 60 * 5 * 1000;

  private final DownloadManager downloadManager;
  private final Context context;
  private final ModelFileManager fileManager;
  private final SharedPreferencesUtil sharedPreferencesUtil;
  private final FirebaseMlLogger eventLogger;

  private final DataTransportMlEventSender statsSender;

  @GuardedBy("this")
  // Mapping from download id to broadcast receiver. Because models can update, we cannot just keep
  // one instance of DownloadBroadcastReceiver per RemoteModelDownloadManager object.
  private final LongSparseArray<DownloadBroadcastReceiver> receiverMaps = new LongSparseArray<>();

  @GuardedBy("this")
  // Mapping from download id to TaskCompletionSource. Because models can update, we cannot just
  // keep one instance of TaskCompletionSource per RemoteModelDownloadManager object.
  private final LongSparseArray<TaskCompletionSource<Void>> taskCompletionSourceMaps =
      new LongSparseArray<>();

  private CustomModelDownloadConditions downloadConditions =
      new CustomModelDownloadConditions.Builder().build();

  public ModelFileDownloadService(
      @NonNull FirebaseApp firebaseApp, TransportFactory transportFactory) {
    this.context = firebaseApp.getApplicationContext();
    downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
    this.fileManager = ModelFileManager.getInstance();
    this.sharedPreferencesUtil = new SharedPreferencesUtil(firebaseApp);
    this.statsSender = DataTransportMlEventSender.create(transportFactory);
    this.eventLogger = new FirebaseMlLogger(firebaseApp, sharedPreferencesUtil, statsSender);
  }

  @VisibleForTesting
  ModelFileDownloadService(
      @NonNull FirebaseApp firebaseApp,
      DownloadManager downloadManager,
      ModelFileManager fileManager,
      SharedPreferencesUtil sharedPreferencesUtil,
      DataTransportMlEventSender statsSender,
      FirebaseMlLogger eventLogger) {
    this.context = firebaseApp.getApplicationContext();
    this.downloadManager = downloadManager;
    this.fileManager = fileManager;
    this.sharedPreferencesUtil = sharedPreferencesUtil;
    this.eventLogger = eventLogger;
    this.statsSender = statsSender;
  }

  /**
   * Get ModelFileDownloadService instance using the firebase app returned by {@link
   * FirebaseApp#getInstance()}
   *
   * @return ModelFileDownloadService
   */
  @NonNull
  public static ModelFileDownloadService getInstance() {
    return FirebaseApp.getInstance().get(ModelFileDownloadService.class);
  }

  public Task<Void> download(
      CustomModel customModel, CustomModelDownloadConditions downloadConditions) {
    this.downloadConditions = downloadConditions;
    // todo add url tests here
    return ensureModelDownloaded(customModel);
  }

  @VisibleForTesting
  Task<Void> ensureModelDownloaded(CustomModel customModel) {
    // todo add logging for explicitly requested
    // check model download already in progress
    CustomModel downloadingModel =
        sharedPreferencesUtil.getDownloadingCustomModelDetails(customModel.getName());
    if (downloadingModel != null) {
      if (downloadingModel.getDownloadId() != 0
          && existTaskCompletionSourceInstance(downloadingModel.getDownloadId())) {
        Integer statusCode = getDownloadingModelStatusCode(downloadingModel.getDownloadId());
        Date now = new Date();

        // check if download has completed or still has time to finish.
        // Give a buffer above url expiry to continue if in progress.
        if (statusCode != null
            && (statusCode == DownloadManager.STATUS_SUCCESSFUL
                || statusCode == DownloadManager.STATUS_FAILED
                || (customModel.getDownloadUrlExpiry()
                    > (now.getTime() - COMPLETION_BUFFER_IN_MS)))) {
          // download in progress - return this task result.
          return getExistingDownloadTask(downloadingModel.getDownloadId());
        }
      }
      // remove previous failed download attempts
      removeOrCancelDownloadModel(downloadingModel.getName(), downloadingModel.getDownloadId());
    }

    // schedule new download of model file
    Long newDownloadId = scheduleModelDownload(customModel);
    if (newDownloadId == null) {
      return Tasks.forException(new Exception("Failed to schedule the download task"));
    }

    return registerReceiverForDownloadId(newDownloadId, customModel.getName());
  }

  /** Removes or cancels the downloading model if exists. */
  synchronized void removeOrCancelDownloadModel(String modelName, Long downloadId) {
    if (downloadManager != null && downloadId != 0) {
      downloadManager.remove(downloadId);
      // TODO(annzimmer) - should I clean up the receiverMaps/TaskCompletionsSource if they are
      // active?
      // should this be sending a failure or just remove from list?
    }
    sharedPreferencesUtil.clearDownloadCustomModelDetails(modelName);
  }

  private synchronized DownloadBroadcastReceiver getReceiverInstance(
      long downloadId, String modelName) {
    DownloadBroadcastReceiver receiver = this.receiverMaps.get(downloadId);
    if (receiver == null) {
      receiver =
          new DownloadBroadcastReceiver(
              downloadId, modelName, getTaskCompletionSourceInstance(downloadId));
      this.receiverMaps.put(downloadId, receiver);
    }
    return receiver;
  }

  private synchronized void removeDownloadTaskInstance(long downloadId) {
    this.taskCompletionSourceMaps.remove(downloadId);
    this.receiverMaps.remove(downloadId);
  }

  private Task<Void> registerReceiverForDownloadId(long downloadId, String modelName) {
    BroadcastReceiver broadcastReceiver = getReceiverInstance(downloadId, modelName);
    // It is okay to always register here. Since the broadcast receiver is the same via the lookup
    // for the same download id, the same broadcast receiver will be notified only once.
    context.registerReceiver(
        broadcastReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));

    return getTaskCompletionSourceInstance(downloadId).getTask();
  }

  /**
   * Returns the in progress download task if one exists, otherwise returns null.
   *
   * @param downloadId - download id associated with the requested task.
   */
  @Nullable
  public Task<Void> getExistingDownloadTask(long downloadId) {
    if (existTaskCompletionSourceInstance(downloadId)) {
      return getTaskCompletionSourceInstance(downloadId).getTask();
    }
    return null;
  }

  @VisibleForTesting
  synchronized TaskCompletionSource<Void> getTaskCompletionSourceInstance(long downloadId) {
    TaskCompletionSource<Void> taskCompletionSource = this.taskCompletionSourceMaps.get(downloadId);
    if (taskCompletionSource == null) {
      taskCompletionSource = new TaskCompletionSource<>();
      this.taskCompletionSourceMaps.put(downloadId, taskCompletionSource);
    }

    return taskCompletionSource;
  }

  @VisibleForTesting
  synchronized boolean existTaskCompletionSourceInstance(long downloadId) {
    TaskCompletionSource<Void> taskCompletionSource = this.taskCompletionSourceMaps.get(downloadId);
    return (taskCompletionSource != null);
  }

  @VisibleForTesting
  synchronized Long scheduleModelDownload(@NonNull CustomModel customModel) {
    if (downloadManager == null) {
      return null;
    }

    if (customModel.getDownloadUrl() == null || customModel.getDownloadUrl().isEmpty()) {
      return null;
    }
    // todo handle expired url here and figure out what to do about delayed downloads too..

    // Schedule a new downloading
    Request downloadRequest = new Request(Uri.parse(customModel.getDownloadUrl()));
    // check Url is not expired - get new one if necessary...

    // By setting the destination uri to null, the downloaded file will be stored in
    // DownloadManager's purgeable cache. As a result, WRITE_EXTERNAL_STORAGE permission is not
    // needed.
    downloadRequest.setDestinationUri(null);
    if (VERSION.SDK_INT >= VERSION_CODES.N) {
      downloadRequest.setRequiresCharging(downloadConditions.isChargingRequired());
      downloadRequest.setRequiresDeviceIdle(downloadConditions.isDeviceIdleRequired());
    }

    if (downloadConditions.isWifiRequired()) {
      downloadRequest.setAllowedNetworkTypes(Request.NETWORK_WIFI);
    }

    long id = downloadManager.enqueue(downloadRequest);
    // update the custom model to store the download id - do not lose current local file - in case
    // this is a background update.
    sharedPreferencesUtil.setDownloadingCustomModelDetails(
        new CustomModel(
            customModel.getName(),
            customModel.getModelHash(),
            customModel.getSize(),
            id,
            customModel.getLocalFilePath()));
    return id;
  }

  @Nullable
  @VisibleForTesting
  synchronized Integer getDownloadingModelStatusCode(Long downloadingId) {
    if (downloadManager == null || downloadingId == null) {
      return null;
    }

    Integer statusCode = null;

    try (Cursor cursor = downloadManager.query(new Query().setFilterById(downloadingId))) {

      if (cursor != null && cursor.moveToFirst()) {
        statusCode = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_STATUS));
      }

      if (statusCode == null) {
        return null;
      }

      if (statusCode != DownloadManager.STATUS_RUNNING
          && statusCode != DownloadManager.STATUS_PAUSED
          && statusCode != DownloadManager.STATUS_PENDING
          && statusCode != DownloadManager.STATUS_SUCCESSFUL
          && statusCode != DownloadManager.STATUS_FAILED) {
        // Unknown status
        statusCode = null;
      }
      return statusCode;
    }
  }

  @Nullable
  private synchronized ParcelFileDescriptor getDownloadedFile(Long downloadingId) {
    if (downloadManager == null || downloadingId == null) {
      return null;
    }

    ParcelFileDescriptor fileDescriptor = null;
    try {
      fileDescriptor = downloadManager.openDownloadedFile(downloadingId);
    } catch (FileNotFoundException e) {
      // todo replace with FirebaseMlException
      Log.d(TAG, "Downloaded file is not found: " + e);
    }
    return fileDescriptor;
  }

  public void maybeCheckDownloadingComplete() {
    for (String key : sharedPreferencesUtil.getSharedPreferenceKeySet()) {
      // if a local file path is present - get model details.
      Matcher matcher =
          Pattern.compile(SharedPreferencesUtil.DOWNLOADING_MODEL_ID_MATCHER).matcher(key);
      if (matcher.find()) {
        String modelName = matcher.group(matcher.groupCount());
        CustomModel downloadingModel = sharedPreferencesUtil.getCustomModelDetails(modelName);
        if (downloadingModel != null) {
          Integer statusCode = getDownloadingModelStatusCode(downloadingModel.getDownloadId());
          if (statusCode != null
              && (statusCode == DownloadManager.STATUS_SUCCESSFUL
                  || statusCode == DownloadManager.STATUS_FAILED)) {
            loadNewlyDownloadedModelFile(downloadingModel);
          }
        }
      }
    }
  }

  @Nullable
  @WorkerThread
  public File loadNewlyDownloadedModelFile(CustomModel model) {
    if (model == null) {
      return null;
    }

    Long downloadingId = model.getDownloadId();
    String downloadingModelHash = model.getModelHash();

    if (downloadingId == 0 || downloadingModelHash.isEmpty()) {
      // Clear the downloading info completely.
      // It handles the case: developer clear the app cache but downloaded model file in
      // DownloadManager's cache would not be cleared.
      removeOrCancelDownloadModel(model.getName(), model.getDownloadId());
      return null;
    }

    Integer statusCode = getDownloadingModelStatusCode(downloadingId);
    if (statusCode == null) {
      Log.d(TAG, "Download failed - no download status available.");
      // No status code, it may mean no such download or no download manager.
      removeOrCancelDownloadModel(model.getName(), model.getDownloadId());
      return null;
    }

    if (statusCode == DownloadManager.STATUS_SUCCESSFUL) {
      // Get downloaded file.
      ParcelFileDescriptor fileDescriptor = getDownloadedFile(downloadingId);
      if (fileDescriptor == null) {
        // reset original model - removing download id.
        removeOrCancelDownloadModel(model.getName(), model.getDownloadId());
        // todo log this?
        return null;
      }

      // Try to move it to destination folder.
      File newModelFile;
      try {
        // TODO add logging
        newModelFile = fileManager.moveModelToDestinationFolder(model, fileDescriptor);
      } catch (FirebaseMlException ex) {
        // add logging for this error
        newModelFile = null;
      } finally {
        removeOrCancelDownloadModel(model.getName(), model.getDownloadId());
      }

      if (newModelFile == null) {
        return null;
      }

      // Successfully moved,  update share preferences
      sharedPreferencesUtil.setLoadedCustomModelDetails(
          new CustomModel(
              model.getName(), model.getModelHash(), model.getSize(), 0, newModelFile.getPath()));

      // todo(annzimmer) Cleans up the old files if it is the initial creation.
      return newModelFile;
    } else if (statusCode == DownloadManager.STATUS_FAILED) {
      // reset original model - removing downloading details.
      removeOrCancelDownloadModel(model.getName(), model.getDownloadId());
    }
    // Other cases, return as null and wait for download finish.
    return null;
  }

  private FirebaseMlException getExceptionAccordingToDownloadManager(Long downloadId) {
    int errorCode = FirebaseMlException.INTERNAL;
    String errorMessage = "Model downloading failed";
    Cursor cursor =
        (downloadManager == null || downloadId == null)
            ? null
            : downloadManager.query(new Query().setFilterById(downloadId));
    if (cursor != null && cursor.moveToFirst()) {
      int reason = cursor.getInt(cursor.getColumnIndex(DownloadManager.COLUMN_REASON));
      if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) {
        errorMessage = "Model downloading failed due to insufficient space on the device.";
        errorCode = FirebaseMlException.NOT_ENOUGH_SPACE;
      } else {
        errorMessage =
            "Model downloading failed due to error code: "
                + reason
                + " from Android DownloadManager";
      }
    }
    return new FirebaseMlException(errorMessage, errorCode);
  }

  /**
   * Gets the failure reason for the {@code downloadId}. Returns 0 if there isn't a record for the
   * specified {@code downloadId}.
   */
  int getFailureReason(Long downloadId) {
    int failureReason = FirebaseMlLogEvent.NO_INT_VALUE;
    Cursor cursor =
        (downloadManager == null || downloadId == null)
            ? null
            : downloadManager.query(new Query().setFilterById(downloadId));
    if (cursor != null && cursor.moveToFirst()) {
      int index = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
      if (index != -1) {
        failureReason = cursor.getInt(index);
      }
    }
    return failureReason;
  }

  // This class runs totally on worker thread because we registered the receiver with a worker
  // thread handler.
  @WorkerThread
  private class DownloadBroadcastReceiver extends BroadcastReceiver {

    // Download Id is captured inside this class in memory. So there is no concern of inconsistency
    // with the persisted download id in shared preferences.
    private final long downloadId;
    private final String modelName;
    private final TaskCompletionSource<Void> taskCompletionSource;

    private DownloadBroadcastReceiver(
        long downloadId, String modelName, TaskCompletionSource<Void> taskCompletionSource) {
      this.downloadId = downloadId;
      this.modelName = modelName;
      this.taskCompletionSource = taskCompletionSource;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
      long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
      System.out.println("Incoming intent: " + intent + " for: " + id);
      if (id != downloadId) {
        return;
      }

      // check to prevent DuplicateTaskCompletionException - this was already updated and removed.
      // Just return.
      if (!existTaskCompletionSourceInstance(downloadId)) {
        System.out.println("Duplicate removed");
        removeDownloadTaskInstance(downloadId);
        return;
      }

      Integer statusCode = getDownloadingModelStatusCode(downloadId);
      // check to prevent DuplicateTaskCompletionException - this was already updated and removed.
      // Just return.
      if (!existTaskCompletionSourceInstance(downloadId)) {
        removeDownloadTaskInstance(downloadId);
        return;
      }

      synchronized (ModelFileDownloadService.this) {
        try {
          context.getApplicationContext().unregisterReceiver(this);
        } catch (IllegalArgumentException e) {
          // If we try to unregister a receiver that was never registered or has been unregistered,
          // IllegalArgumentException will be thrown by the Android Framework.
          // Our current code does not have this problem. However, in order to be safer in the
          // future, we just ignore the exception here, because it is not a big deal. The code can
          // move on.
        }

        removeDownloadTaskInstance(downloadId);
      }

      if (statusCode != null) {
        if (statusCode == DownloadManager.STATUS_FAILED) {
          int failureReason = getFailureReason(id);
          CustomModel downloadingModel =
              sharedPreferencesUtil.getDownloadingCustomModelDetails(modelName);
          if (downloadingModel != null) {
            eventLogger.logDownloadFailureWithReason(downloadingModel, false, failureReason);
            if (checkErrorCausedByExpiry(downloadingModel.getDownloadUrlExpiry(), failureReason)) {
              // retry as a new download
              // todo change to FirebaseMlException retry error - or whatever we decide is
              // appropriate.
              taskCompletionSource.setException(new Exception("Retry: Expired URL"));
              return;
            }
          }
          taskCompletionSource.setException(getExceptionAccordingToDownloadManager(id));
          return;
        }

        if (statusCode == DownloadManager.STATUS_SUCCESSFUL) {
          CustomModel model = sharedPreferencesUtil.getDownloadingCustomModelDetails(modelName);
          if (model == null) {
            // model might have been updated already get the downloaded model.
            model = sharedPreferencesUtil.getCustomModelDetails(modelName);
            if (model == null) {
              // todo add logging here.
              taskCompletionSource.setException(
                  new FirebaseMlException(
                      "No model associated with name: " + modelName,
                      FirebaseMlException.INVALID_ARGUMENT));
              return;
            }
          }
          eventLogger.logDownloadEventWithExactDownloadTime(
              model, ErrorCode.NO_ERROR, DownloadStatus.SUCCEEDED);
          taskCompletionSource.setResult(null);
          return;
        }
      }

      // Status code is null or not one of success or fail.
      taskCompletionSource.setException(new Exception("Model downloading failed"));
    }

    private boolean checkErrorCausedByExpiry(long downloadUrlExpiry, int failureReason) {
      final Date time = new Date();
      return (failureReason == 400 && downloadUrlExpiry < time.getTime());
    }
  }
}