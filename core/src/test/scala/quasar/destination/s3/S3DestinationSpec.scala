/*
 * Copyright 2014–2019 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.destination.s3

import slamdata.Predef._

import quasar.EffectfulQSpec
import quasar.api.destination.ResultSink
import quasar.api.resource.{ResourceName, ResourcePath}
import quasar.connector.ResourceError
import quasar.contrib.scalaz.MonadError_

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.Implicits.global

import cats.data.NonEmptyList
import cats.effect.concurrent.Ref
import cats.effect.{IO, Timer}
import cats.instances.string._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.foldable._
import cats.syntax.option._
import fs2.{Stream, text}
import shims._

object S3DestinationSpec extends EffectfulQSpec[IO] {
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)

  val TestBucket = Bucket("fake-bucket")

  "duplicates the filename as a prefix" >>* {
    for {
      (upload, ref) <- MockUpload.empty
      testPath = ResourcePath.root() / ResourceName("foo") / ResourceName("bar.csv")
      bytes = Stream("foobar").through(text.utf8Encode)
      _ <- run(upload, testPath, bytes)
      keys <- ref.get.map(_.keys)
    } yield {
      keys must contain(exactly(ObjectKey("foo/bar/bar.csv")))
    }
  }

  "rejects ResourcePath.root() with ResourceError.NotAResource" >>* {
    for {
      (upload, _) <- MockUpload.empty
      testPath = ResourcePath.root()
      bytes = Stream.empty
      res <- run(upload, testPath, bytes).map(_.asRight[ResourceError]) recover {
        case ResourceError.throwableP(re) => re.asLeft[Unit]
      }
    } yield {
      res must beLeft.like {
        case ResourceError.NotAResource(path) => path must_== testPath
      }
    }
  }

  "uploads results" >>* {
    for {
      (upload, ref) <- MockUpload.empty
      testPath = ResourcePath.root() / ResourceName("foo") / ResourceName("bar.csv")
      bytes = Stream("push this").through(text.utf8Encode)
      _ <- run(upload, testPath, bytes)
      currentStatus <- ref.get
    } yield {
      currentStatus.get(ObjectKey("foo/bar/bar.csv")) must beSome("push this")
    }
  }

  private def run(upload: Upload[IO], path: ResourcePath, bytes: Stream[IO, Byte]): IO[Unit] =
    findCsvSink(S3Destination[IO](TestBucket, upload).sinks.asCats).fold(
      IO.raiseError[Unit](new Exception("Could not find CSV sink in S3Destination"))
    )(_.run(path, List(), bytes).compile.drain)

  private def findCsvSink(sinks: NonEmptyList[ResultSink[IO]]): Option[ResultSink.Csv[IO]] =
    sinks collectFirstSome {
      case csvSink @ ResultSink.Csv(_, _) => csvSink.some
      case _ => None
    }

  private implicit val ioMonadResourceErr: MonadError_[IO, ResourceError] =
    MonadError_.facet[IO](ResourceError.throwableP)
}

final class MockUpload(status: Ref[IO, Map[ObjectKey, String]]) extends Upload[IO] {
  def upload(bytes: Stream[IO, Byte], bucket: Bucket, key: ObjectKey): Stream[IO, Unit] =
    for {
      data <- bytes.through(text.utf8Decode).foldMonoid
      _ <- Stream.eval(status.update(_ + (key -> data)))
    } yield ()
}

object MockUpload {
  def empty: IO[(Upload[IO], Ref[IO, Map[ObjectKey, String]])] =
   Ref.of[IO, Map[ObjectKey, String]](Map.empty[ObjectKey, String])
     .map(ref => (new MockUpload(ref), ref))
}
