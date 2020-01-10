/***********************************************************************
 * Copyright (c) 2013-2019 Commonwealth Computer Research, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 ***********************************************************************/

package org.locationtech.geomesa

package object convert {

  @deprecated("Use evaluation context metrics")
  trait Counter {
    def incSuccess(i: Long = 1): Unit
    def getSuccess: Long

    def incFailure(i: Long = 1): Unit
    def getFailure: Long

    // For things like Avro think of this as a recordCount as well
    def incLineCount(i: Long = 1): Unit
    def getLineCount: Long
    def setLineCount(i: Long)
  }

  // noinspection ScalaDeprecation
  @deprecated("Use evaluation context metrics")
  class DefaultCounter extends Counter {
    private var s: Long = 0
    private var f: Long = 0
    private var c: Long = 0

    override def incSuccess(i: Long = 1): Unit = s += i
    override def getSuccess: Long = s

    override def incFailure(i: Long = 1): Unit = f += i
    override def getFailure: Long = f

    override def incLineCount(i: Long = 1): Unit = c += i
    override def getLineCount: Long = c
    override def setLineCount(i: Long): Unit = c = i
  }
}
