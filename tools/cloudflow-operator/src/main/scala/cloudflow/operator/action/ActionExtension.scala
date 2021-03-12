/*
 * Copyright (C) 2016-2021 Lightbend Inc. <https://www.lightbend.com>
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

package cloudflow.operator.action

import akka.kube.actions.Action
import io.fabric8.kubernetes.api.model.{ Secret, SecretList }
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.{ MixedOperation, Resource }

import scala.util.{ Failure, Success, Try }

object ActionExtension {

  // TODO: re-test with IT tests
  def providedRetry(name: String, namespace: String)(fAction: Option[Secret] => Action)(retry: Int = 60)(
      implicit lineNumber: sourcecode.Line,
      file: sourcecode.File): Action = { // TODO: 60 looks quite a lot!
    Action.operation[Secret, SecretList, Try[Secret]](
      { client: KubernetesClient => client.secrets() }, {
        secrets: MixedOperation[Secret, SecretList, Resource[Secret]] =>
          Try(
            secrets
              .inNamespace(namespace)
              .withName(name)
              .fromServer()
              .get())
      }, { res =>
        res match {
          case Success(s) if s != null => fAction(Option(s))
          case Success(null) =>
            Action.log.error(s"Retry to get $name in $namespace, was null, retries: $retry")
            providedRetry(name, namespace)(fAction)(retry - 1)
          case Failure(_) if retry > 0 =>
            Action.log.error(s"Retry exhausted while trying to get $name in $namespace, retries: $retry")
            providedRetry(name, namespace)(fAction)(retry - 1)
          case Failure(err) =>
            Action.log.error(s"Retry exhausted while trying to get $name in $namespace, giving up")
            throw err
        }
      })

  }

}
