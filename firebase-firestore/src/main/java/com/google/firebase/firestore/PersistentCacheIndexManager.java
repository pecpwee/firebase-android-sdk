// Copyright 2023 Google LLC
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

package com.google.firebase.firestore;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;
import com.google.firebase.firestore.core.FirestoreClient;

/**
 * A {@code PersistentCacheIndexManager} which you can config persistent cache indexes used for
 * local query execution.
 *
 * <p>To use, call {@link FirebaseFirestore#getPersistentCacheIndexManager()} to get an instance.
 */
public final class PersistentCacheIndexManager {
  @NonNull private FirestoreClient client;

  /** @hide */
  @RestrictTo(RestrictTo.Scope.LIBRARY)
  PersistentCacheIndexManager(FirestoreClient client) {
    this.client = client;
  }

  /**
   * Enables SDK to create persistent cache indexes automatically for local query execution when SDK
   * believes cache indexes can help improves performance.
   *
   * <p>This feature is disabled by default.
   */
  public void enableIndexAutoCreation() {
    client.setIndexAutoCreationEnabled(true);
  }

  /**
   * Stops creating persistent cache indexes automatically for local query execution. The indexes
   * which have been created by calling {@link #enableIndexAutoCreation()} still take effect.
   */
  public void disableIndexAutoCreation() {
    client.setIndexAutoCreationEnabled(false);
  }

  /**
   * Removes all persistent cache indexes. Please note this function also deletes indexes generated
   * by {@link FirebaseFirestore#setIndexConfiguration(String)}, which is deprecated.
   */
  public void deleteAllIndexes() {
    client.deleteAllFieldIndexes();
  }
}