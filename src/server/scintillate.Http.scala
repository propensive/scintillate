/*
    Scintillate, version [unreleased]. Copyright 2024 Jon Pretty, Propensive OÜ.

    The primary distribution site is: https://propensive.com/

    Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
    file except in compliance with the License. You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
    either express or implied. See the License for the specific language governing permissions
    and limitations under the License.
*/

package scintillate

import anticipation.*
import contingency.*
import nettlesome.*
import parasite.*
import telekinesis.*

erased trait Http

object Http:
  given (using Monitor, Codicil, HttpServerEvent is Loggable, Tactic[ServerError])
      => Http is Protocolic:
    type Carrier = TcpPort
    type Request = HttpConnection
    type Response = telekinesis.HttpResponse
    type Server = HttpService

    def server(port: TcpPort)(handler: HttpConnection ?=> HttpResponse): HttpService =
      HttpServer(port.number).listen(handler)
