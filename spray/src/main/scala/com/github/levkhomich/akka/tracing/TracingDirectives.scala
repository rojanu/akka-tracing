/**
 * Copyright 2014 the Akka Tracing contributors. See AUTHORS for more details.
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

package com.github.levkhomich.akka.tracing

import spray.routing._
import shapeless._
import spray.httpx.unmarshalling._
import spray.httpx.marshalling.ToResponseMarshaller
import spray.routing.directives.RouteDirectives
import spray.http.HttpRequest

final case class Span(traceId: Long, spanId: Long, parentId: Option[Long])

trait TracingDirectives {

  import spray.routing.directives.BasicDirectives._
  import spray.routing.directives.RouteDirectives._
  import spray.routing.directives.MiscDirectives._
  import TracingHeaders._

//  def optionalTracing: Directive[Option[Span] :: HNil] =
//    (
//      optionalHeaderValueByName(HttpTracing.Header.TraceId) &
//      optionalHeaderValueByName(HttpTracing.Header.SpanId) &
//      optionalHeaderValueByName(HttpTracing.Header.ParentSpanId)
//    ) hmap {
//      case Some(traceId) :: Some(spanId) :: parentId :: HNil =>
//        Some(Span(traceId, spanId, parentId)) :: HNil
//      case _ =>
//        None :: HNil
//    }

  private def headerByName(request: HttpRequest, name: String): Option[String] =
    request.headers.find(_.name == name).map(_.value)

  private def extractSpan(request: HttpRequest): Option[Span] = {
    headerByName(request, TraceId) -> headerByName(request, SpanId) match {
      case (Some(traceId), Some(spanId)) =>
        try {
          Some(Span(traceId.toLong, spanId.toLong, headerByName(request, ParentSpanId).map(_.toLong)))
        } catch {
          case e: NumberFormatException =>
            None
        }
      case _ =>
        None
    }
  }

  def tracingHandleWith[A <: TracingSupport, B](f: A ⇒ B)(implicit um: FromRequestUnmarshaller[A], m: ToResponseMarshaller[B]): Route =
    (hextract(ctx => ctx.request.as(um) :: extractSpan(ctx.request) :: HNil).hflatMap[A :: Option[Span] :: HNil] {
      case Right(value) :: optSpan :: HNil =>
        optSpan.foreach(s => value.init(s.spanId, s.traceId, s.parentId))
        hprovide(value :: optSpan :: HNil)

      case Left(ContentExpected) :: _ =>
        reject(RequestEntityExpectedRejection)

      case Left(UnsupportedContentType(supported)) :: _ =>
        reject(UnsupportedRequestContentTypeRejection(supported))

      case Left(MalformedContent(errorMsg, cause)) :: _ =>
        reject(MalformedRequestContentRejection(errorMsg, cause))

    } & cancelAllRejections(ofTypes(RequestEntityExpectedRejection.getClass, classOf[UnsupportedRequestContentTypeRejection])))
    {
      case (a, optTrace) => RouteDirectives.complete(f(a))
    }
}

object TracingDirectives extends TracingDirectives
