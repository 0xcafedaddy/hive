/**
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
package org.apache.hadoop.hive.serde2.lazy;

/**
 * LazyObject for storing a value of Integer.
 * 
 * <p>
 * Part of the code is adapted from Apache Harmony Project.
 * 
 * As with the specification, this implementation relied on code laid out in <a
 * href="http://www.hackersdelight.org/">Henry S. Warren, Jr.'s Hacker's
 * Delight, (Addison Wesley, 2002)</a> as well as <a
 * href="http://aggregate.org/MAGIC/">The Aggregate's Magic Algorithms</a>.
 * </p>
 * 
 */
public class LazyInteger extends LazyPrimitive<Integer> {

  public LazyInteger() {
    super(Integer.class);
  }
  
  @Override
  public Integer getPrimitiveObject() {
    try {
      // Slower method: convert to String and then convert to Integer
      // return Integer.valueOf(LazyUtils.convertToString(bytes, start, length));
      return Integer.valueOf(parseInt(bytes, start, length));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * Parses the string argument as if it was an int value and returns the
   * result. Throws NumberFormatException if the string does not represent an
   * int quantity.
   * 
   * @param bytes
   * @param start
   * @param length
   *            a UTF-8 encoded string representation of an int quantity.
   * @return int the value represented by the argument
   * @exception NumberFormatException
   *                if the argument could not be parsed as an int quantity.
   */
  public static int parseInt(byte[] bytes, int start, int length) throws NumberFormatException {
      return parseInt(bytes, start, length, 10);
  }

  /**
   * Parses the string argument as if it was an int value and returns the
   * result. Throws NumberFormatException if the string does not represent an
   * int quantity. The second argument specifies the radix to use when parsing
   * the value.
   * 
   * @param bytes
   * @param start
   * @param length
   *            a UTF-8 encoded string representation of an int quantity.
   * @param radix
   *            the base to use for conversion.
   * @return int the value represented by the argument
   * @exception NumberFormatException
   *                if the argument could not be parsed as an int quantity.
   */
  public static int parseInt(byte[] bytes, int start, int length, int radix)
          throws NumberFormatException {
    if (bytes == null) {
      throw new NumberFormatException("String is null");
    }
    if (radix < Character.MIN_RADIX ||
        radix > Character.MAX_RADIX) {
      throw new NumberFormatException("Invalid radix: " + radix);
    }
    if (length == 0) {
      throw new NumberFormatException("Empty string!");
    }
    int offset = start;
    boolean negative = bytes[start] == '-';
    if (negative || bytes[start] == '+') {
      offset++;
      if (length == 1) {
        throw new NumberFormatException(LazyUtils.convertToString(bytes, start, length));
      }
    }

    return parse(bytes, start, length, offset, radix, negative);
  }

  private static int parse(byte[] bytes, int start, int length, int offset, int radix,
          boolean negative) throws NumberFormatException {
      int max = Integer.MIN_VALUE / radix;
      int result = 0, end = start + length;
      while (offset < end) {
          int digit = LazyUtils.digit(bytes[offset++], radix);
          if (digit == -1) {
              throw new NumberFormatException(LazyUtils.convertToString(bytes, start, length));
          }
          if (max > result) {
              throw new NumberFormatException(LazyUtils.convertToString(bytes, start, length));
          }
          int next = result * radix - digit;
          if (next > result) {
              throw new NumberFormatException(LazyUtils.convertToString(bytes, start, length));
          }
          result = next;
      }
      if (!negative) {
          result = -result;
          if (result < 0) {
              throw new NumberFormatException(LazyUtils.convertToString(bytes, start, length));
          }
      }
      return result;
  }
  
}
