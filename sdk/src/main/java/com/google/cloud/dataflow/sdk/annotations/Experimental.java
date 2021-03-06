/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.sdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Signifies that a public API (public class, method or field) is subject to
 * incompatible changes, or even removal, in a future release. An API bearing
 * this annotation is exempt from any compatibility guarantees made by its
 * containing library. Note that the presence of this annotation implies nothing
 * about the quality or performance of the API in question, only the fact that
 * it is not "API-frozen."
 *
 * <p>It is generally safe for <i>applications</i> to depend on experimental
 * APIs, at the cost of some extra work during upgrades. However, it is
 * generally inadvisable for <i>libraries</i> (which get included on users'
 * class paths, outside the library developers' control) to do so.
 */
@Retention(RetentionPolicy.CLASS)
@Target({
    ElementType.ANNOTATION_TYPE,
    ElementType.CONSTRUCTOR,
    ElementType.FIELD,
    ElementType.METHOD,
    ElementType.TYPE})
@Documented
public @interface Experimental {
  public Kind value() default Kind.UNSPECIFIED;

  /**
   * An enumeration of various kinds of experimental APIs.
   */
  public enum Kind {
    /** Generic group of experimental APIs. This is the default value. */
    UNSPECIFIED,

    /** Sources and sinks related experimental APIs. */
    SOURCE_SINK,

    /** Auto-scaling related experimental APIs. */
    AUTOSCALING,

    /** Trigger-related experimental APIs. */
    TRIGGER,

    /** Aggregator-related experimental APIs. */
    AGGREGATOR,

    /** Experimental APIs for Coder binary format identifiers. */
    CODER_ENCODING_ID,

    /** State-related experimental APIs. */
    STATE,

    /** Experimental APIs related to customizing the output time for computed values. */
    OUTPUT_TIME
  }
}
