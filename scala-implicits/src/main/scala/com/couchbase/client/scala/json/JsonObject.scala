/*
 * Copyright (c) 2018 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.scala.json

import java.util

import com.couchbase.client.core.error.DecodingFailedException
import com.fasterxml.jackson.core.JsonProcessingException

import scala.collection.{GenSet, mutable}
import scala.language.dynamics
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

//import com.fasterxml.jackson.databind.ObjectMapper

import scala.collection.JavaConverters._

/** A representation of a JSON object.
  *
  * Use the companion class to construct one.
  *
  * There are plenty of JSON libraries for Scala already.  So why make another one?
  *
  * The Couchbase Scala SDK does provide support for several popular JSON libraries already, but during benchmarking
  * it became clear that there were big performance differences between these and the simple `JsonObject` JSON API
  * provided with the Java SDK.
  *
  * There was also some questions about ease-of-use, with most of the Scala JSON libraries leaning towards
  * immutability.  Mutating deeply nested immutable data, such as JSON, requires advanced functional concepts such as
  * optics and zippers, and these aren't always easy to read or use.
  *
  * So the mini JSON library made up of `JsonObject` here and [[JsonArray]] are intended to fill a perceived niche by
  * providing two main advantages over the majority of Scala JSON libraries: speed and ease-of-use.
  *
  * 1. Speed.  This library is focused 100% purely on performance, and benchmarking indicates it's at least
  * three times faster to create a `JsonObject` with a few fields than to perform the equivalent operation in the
  * next-fastest Scala JSON library.
  *
  * 2. Ease-of-use.  The objects are mutable, which may raise an eyebrow for functional Scala programmers but does
  * allow a very simple API with no zippers, lenses, prisms or optics.
  *
  * In general the Couchbase Scala SDK returns `Try` rather than throwing exceptions.  This interface is one of the
  * rare exceptions to this rule, and many methods will throw exceptions, for instance if a field is requested that
  * does
  * not exist.  If you would rather use a more functional interface then please see [[JsonObjectSafe]] and
  * [[JsonArraySafe]], which provide a 'safe' wrapper that performs all operations with Scala `Try`s.
  *
  * @define SupportedType     only certain types are allowed to be put into this.  They are: [[JsonObject]] and
  *                           [[JsonObjectSafe]], [[JsonArray]] and [[JsonArraySafe]], Int, Double, Short, Long, Float,
  *                           Boolean.
  * @define SupportedNumTypes the supported number types (Int, Double, Float, Long, Short)
  * @define Name              the field's key
  * @define NotExist          if the field does not exist
  */

case class JsonObject(private[scala] val content: java.util.HashMap[String, Any]) {

  /** Returns a [[GetSelecter]] providing `Dynamic` access to this objects fields. */
  def dyn: GetSelecter = {
    // Implementation note: Instead of making JsonObject itself Dynamic, which lends itself to all kinds of
    // accidental errors, put it in a separate method
    GetSelecter(Left(this), Seq())
  }

  /** Puts a value into this object.
    *
    * This mutates this object.  The returned `JsonObject` is a reference to this, not a copy.
    *
    * @param name  $Name
    * @param value $SupportedType
    * @return a reference to this, to allow chaining operations
    */
  def put(name: String, value: Any): JsonObject = {
    content.put(name, value)
    this
  }

  /** Gets a value from this object.
    *
    * @param name  $Name
    * @throws NoSuchElementException $NotExist
    */
  def get(name: String): Any = {
    val check = content.get(name)
    if (check == null) if (!content.containsKey(name)) throw new NoSuchElementException(s"Field $name does not exist")
    check
  }

  /** Gets a String value from this object.
    *
    * If that value is not itself a String, it's `toString` value will be returned.  If the value is `null`, then
    * `null` will be returned.
    *
    * @param name  $Name
    * @throws NoSuchElementException $NotExist
    */
  def str(name: String): String = {
    val check = content.get(name)
    if (check == null) if (!content.containsKey(name)) throw new NoSuchElementException(s"Field $name does not exist")
    ValueConvertor.str(check, name)
  }

  /** Gets an Int value from this object.
    *
    * If that value is actually an Int it is returned directly.  Else if it is one of $SupportedNumTypes or
    * String it will be converted with `toInt` (which may throw if it cannot be converted).  Else if it is of a
    * different type then DecodingFailedException will be thrown.
    *
    * @param name  $Name
    * @throws NoSuchElementException   $NotExist
    * @throws DecodingFailedException  if the value was not one of $SupportedNumTypes or String
    */
  def num(name: String): Int = {
    val check = content.get(name)
    if (check == null) if (!content.containsKey(name)) throw new NoSuchElementException(s"Field $name does not exist")
    ValueConvertor.num(check, name)
  }

  /** Gets a Boolean value from this object.
    *
    * If that value is actually an Boolean it is returned directly.  Else DecodingFailedException will be thrown.
    *
    * @param name  $Name
    * @throws NoSuchElementException   $NotExist
    * @throws DecodingFailedException  if the value was not a Boolean
    */
  def bool(name: String): Boolean = {
    val check = content.get(name)
    if (check == null) if (!content.containsKey(name)) throw new NoSuchElementException(s"Field $name does not exist")
    ValueConvertor.bool(check, name)
  }

  /** Gets an Long value from this object.
    *
    * If that value is actually an Long it is returned directly.  Else if it is one of $SupportedNumTypes or
    * String it will be converted with `toInt` (which may throw if it cannot be converted).  Else if it is of a
    * different type then DecodingFailedException will be thrown.
    *
    * @param name  $Name
    * @throws NoSuchElementException   $NotExist
    * @throws DecodingFailedException  if the value was not one of $SupportedNumTypes or String
    */
  def numLong(name: String): Long = {
    val check = content.get(name)
    if (check == null) if (!content.containsKey(name)) throw new NoSuchElementException(s"Field $name does not exist")
    ValueConvertor.numLong(check, name)
  }

  /** Gets a Double value from this object.
    *
    * If that value is actually a Double it is returned directly.  Else if it is one of $SupportedNumTypes or
    * String it will be converted with `toInt` (which may throw if it cannot be converted).  Else if it is of a
    * different type then DecodingFailedException will be thrown.
    *
    * @param name  $Name
    * @throws NoSuchElementException   $NotExist
    * @throws DecodingFailedException  if the value was not one of $SupportedNumTypes or String
    */
  def numDouble(name: String): Double = {
    val check = content.get(name)
    if (check == null) if (!content.containsKey(name)) throw new NoSuchElementException(s"Field $name does not exist")
    ValueConvertor.numDouble(check, name)
  }

  /** Gets a Float value from this object.
    *
    * If that value is actually an Float it is returned directly.  Else if it is one of $SupportedNumTypes or
    * String it will be converted with `toInt` (which may throw if it cannot be converted).  Else if it is of a
    * different type then DecodingFailedException will be thrown.
    *
    * @param name  $Name
    * @throws NoSuchElementException   $NotExist
    * @throws DecodingFailedException  if the value was not one of $SupportedNumTypes or String
    */
  def numFloat(name: String): Float = {
    val check = content.get(name)
    if (check == null) if (!content.containsKey(name)) throw new NoSuchElementException(s"Field $name does not exist")
    ValueConvertor.numFloat(check, name)
  }

  /** Gets a `JsonObject` value from this object.
    *
    * If that value is `null`, then `null` will be returned.
    *
    * @param name  $Name
    * @throws NoSuchElementException   $NotExist
    * @throws DecodingFailedException  if the value was not of type `JsonObject`
    */
  def obj(name: String): JsonObject = {
    val check = content.get(name)
    if (check == null) if (!content.containsKey(name)) throw new NoSuchElementException(s"Field $name does not exist")
    ValueConvertor.obj(check, name)
  }

  /** Gets a `JsonArray` value from this object.
    *
    * If that value is `null`, then `null` will be returned.
    *
    * @param name  $Name
    * @throws NoSuchElementException   $NotExist
    * @throws DecodingFailedException  if the value was not of type `JsonArray`
    */
  def arr(name: String): JsonArray = {
    val check = content.get(name)
    if (check == null) if (!content.containsKey(name)) throw new NoSuchElementException(s"Field $name does not exist")
    ValueConvertor.arr(check, name)
  }

  /** Removes a key from this object.
    *
    * This mutates this object.  The returned `JsonObject` is a reference to this, not a copy.
    *
    * @param name  $Name
    * @return a reference to this, to allow chaining operations
    */
  def remove(name: String): JsonObject = {
    content.remove(name)
    this
  }

  /** Returns true if this contains a key.
    *
    * @param name  $Name
    */
  def containsKey(name: String): Boolean = {
    content.containsKey(name)
  }

  /** Returns a `GenSet` of all keys in this. */
  def names: GenSet[String] = {
    content.keySet.asScala
  }

  /** Returns true if this contains no keys. */
  def isEmpty: Boolean = {
    content.isEmpty
  }

  /** Returns true if this contains keys. */
  def nonEmpty: Boolean = {
    !content.isEmpty
  }

  /** Converts this and its contents recursively into Scala collections representing the same JSON.
    *
    * [[JsonObject]] and [[JsonObjectSafe]] will be converted into `GenMap[String, Any]`, and [[JsonArray]] and
    * [[JsonArraySafe]] will be converted into `Seq[Any]`.
    */
  def toMap: collection.GenMap[String, Any] = {
    val copy = new mutable.AnyRefMap[String, Any](content.size)
    import scala.collection.JavaConverters._
    for (entry <- content.entrySet.asScala) {
      val content = entry.getValue
      content match {
        case v: JsonObject => copy.put(entry.getKey, v.toMap)
        case v: JsonObjectSafe => copy.put(entry.getKey, v.toMap)
        case v: JsonArray => copy.put(entry.getKey, v.toSeq)
        case v: JsonArraySafe => copy.put(entry.getKey, v.toSeq)
        case _ => copy.put(entry.getKey, content)
      }
    }
    copy
  }

  /** Returns the number of keys in this. */
  def size: Int = {
    content.size
  }

  /** Recursively converts this and its contents into String representing the same JSON, e.g. """{"foo":"bar"}""".
    *
    * Cannot throw as long as only supported types have been put into this.
    */
  override def toString: String = JacksonTransformers.MAPPER.writeValueAsString(this)

  /** This is an 'unsafe', non-functional interface that can throw.  It's provided for convenience.
    *
    * If you would rather with functional interface that returns `Try` for all operations and does not throw, then
    * use this method to get a [[JsonObjectSafe]] interface.
    */
  def safe = JsonObjectSafe(this)
}

/** Methods to construct a `JsonObject`. */
object JsonObject {

  /** Constructs a `JsonObject` from a String representing valid JSON ("""{"foo":"bar"}""").
    *
    * @throws DecodingFailedException if the String contains invalid JSON
    */
  def fromJson(json: String): JsonObject = {
    try {
      JacksonTransformers.stringToJsonObject(json)
    }
    catch {
      case NonFatal(err) => throw new DecodingFailedException(err)
    }
  }

  /** Constructs an empty `JsonObject`. */
  def create: JsonObject = new JsonObject(new util.HashMap[String, Any]())

  /** Constructs a `JsonObject` from the supplied map.
    *
    * Note, benchmarking indicates it's roughly twice as fast to use `create` and then `put` all fields, so that
    * should be preferred.
    */
  def apply(values: (String, Any)*): JsonObject = {
    val map = new util.HashMap[String, Any](values.size)
    values.foreach(v => map.put(v._1, v._2))
    new JsonObject(map)
  }

  /** Constructs a `JsonObject` from the supplied map.
    *
    * Note, benchmarking indicates it's roughly twice as fast to use `create` and then `put` all fields, so that
    * should be preferred.
    */
  def apply(values: Map[String, Any]): JsonObject = {
    val map = new util.HashMap[String, Any](values.size)
    values.foreach(v => map.put(v._1, v._2))
    new JsonObject(map)
  }
}
