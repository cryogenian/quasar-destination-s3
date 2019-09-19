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

import argonaut.{Argonaut, DecodeJson, DecodeResult}, Argonaut._
import com.amazonaws.services.s3.AmazonS3URI

final case class S3Config(bucket: String, credentials: S3Credentials)

final case class AccessKey(value: String)
final case class SecretKey(value: String)
final case class Region(value: String)

final case class S3Credentials(accessKey: AccessKey, secretKey: SecretKey, region: Region)

object S3Config {
  private implicit val s3CredentialsDecodeJson: DecodeJson[S3Credentials] =
    DecodeJson(c => for {
      accessKey <- c.downField("accessKey").as[String]
      secretKey <- c.downField("secretKey").as[String]
      region <- c.downField("region").as[String]
    } yield S3Credentials(AccessKey(accessKey), SecretKey(secretKey), Region(region)))

  implicit val s3ConfigDecodeJson: DecodeJson[S3Config] =
    DecodeJson(c => for {
      url <- c.downField("bucket").as[String]
      validatedBucket <- bucketName(url).fold[DecodeResult[String]](
        DecodeResult.fail("Unable to parse bucket from URL", c.history))(DecodeResult.ok(_))
      creds <- c.downField("credentials").as[S3Credentials]
    } yield S3Config(validatedBucket, creds))

  private def bucketName(strUrl: String): Option[String] =
    Option((new AmazonS3URI(strUrl)).getBucket)
}
