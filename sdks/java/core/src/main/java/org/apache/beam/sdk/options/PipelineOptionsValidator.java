/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.options;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.Collections2;
import com.google.common.collect.Ordering;
import com.google.common.collect.SortedSetMultimap;
import com.google.common.collect.TreeMultimap;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import org.apache.beam.sdk.options.Validation.Required;
import org.apache.beam.sdk.util.common.ReflectHelpers;

/**
 * Validates that the {@link PipelineOptions} conforms to all the {@link Validation} criteria.
 */
public class PipelineOptionsValidator {
  /**
   * Validates that the passed {@link PipelineOptions} conforms to all the validation criteria from
   * the passed in interface.
   *
   * <p>Note that the interface requested must conform to the validation criteria specified on
   * {@link PipelineOptions#as(Class)}.
   *
   * @param klass The interface to fetch validation criteria from.
   * @param options The {@link PipelineOptions} to validate.
   * @return The type
   */
  public static <T extends PipelineOptions> T validate(Class<T> klass, PipelineOptions options) {
    checkNotNull(klass);
    checkNotNull(options);
    checkArgument(Proxy.isProxyClass(options.getClass()));
    checkArgument(Proxy.getInvocationHandler(options) instanceof ProxyInvocationHandler);

    // Ensure the methods for T are registered on the ProxyInvocationHandler
    T asClassOptions = options.as(klass);

    ProxyInvocationHandler handler =
        (ProxyInvocationHandler) Proxy.getInvocationHandler(asClassOptions);

    SortedSetMultimap<String, Method> requiredGroups = TreeMultimap.create(
        Ordering.natural(), PipelineOptionsFactory.MethodNameComparator.INSTANCE);
    for (Method method : ReflectHelpers.getClosureOfMethodsOnInterface(klass)) {
      Required requiredAnnotation = method.getAnnotation(Validation.Required.class);
      if (requiredAnnotation != null) {
        if (requiredAnnotation.groups().length > 0) {
          for (String requiredGroup : requiredAnnotation.groups()) {
            requiredGroups.put(requiredGroup, method);
          }
        } else {
          checkArgument(handler.invoke(asClassOptions, method, null) != null,
              "Missing required value for [%s, \"%s\"]. ",
              method, getDescription(method));
        }
      }
    }

    for (String requiredGroup : requiredGroups.keySet()) {
      if (!verifyGroup(handler, asClassOptions, requiredGroups.get(requiredGroup))) {
        throw new IllegalArgumentException("Missing required value for group [" + requiredGroup
            + "]. At least one of the following properties "
            + Collections2.transform(
                requiredGroups.get(requiredGroup), ReflectHelpers.METHOD_FORMATTER)
            + " required. Run with --help=" + klass.getSimpleName() + " for more information.");
      }
    }

    return asClassOptions;
  }

  private static boolean verifyGroup(ProxyInvocationHandler handler, PipelineOptions options,
      Collection<Method> requiredGroup) {
    for (Method m : requiredGroup) {
      if (handler.invoke(options, m, null) != null) {
        return true;
      }
    }
    return false;
  }

  private static String getDescription(Method method) {
    Description description = method.getAnnotation(Description.class);
    return description == null ? "" : description.value();
  }
}
