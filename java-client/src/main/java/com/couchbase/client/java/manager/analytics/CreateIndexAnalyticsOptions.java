/*
 * Copyright 2019 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.java.manager.analytics;

import com.couchbase.client.core.annotation.Stability;
import com.couchbase.client.java.CommonOptions;

import java.util.Optional;

public class CreateIndexAnalyticsOptions extends CommonOptions<CreateIndexAnalyticsOptions> {

  private boolean ignoreIfExists;
  private Optional<String> dataverseName = Optional.empty();

  private CreateIndexAnalyticsOptions() {
  }

  public static CreateIndexAnalyticsOptions createIndexAnalyticsOptions() {
    return new CreateIndexAnalyticsOptions();
  }

  public CreateIndexAnalyticsOptions ignoreIfExists(boolean ignore) {
    this.ignoreIfExists = ignore;
    return this;
  }

  public CreateIndexAnalyticsOptions dataverseName(String dataverseName) {
    this.dataverseName = Optional.ofNullable(dataverseName);
    return this;
  }

  @Stability.Internal
  public Built build() {
    return new Built();
  }

  public class Built extends BuiltCommonOptions {
    public boolean ignoreIfExists() {
      return ignoreIfExists;
    }

    public Optional<String> dataverseName() {
      return dataverseName;
    }
  }
}