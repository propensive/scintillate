/*
    Scintillate, version 0.16.0. Copyright 2021-22 Jon Pretty, Propensive OÜ.

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

import rudiments.*
import gossamer.*
import gastronomy.*
import eucalyptus.*
import gesticulate.*
import escapade.*

import java.net.InetSocketAddress
import java.io.*
import java.text as jt
import com.sun.net.httpserver.{HttpServer as JavaHttpServer, *}

case class MissingParamError(key: Text) extends Error:
  def message: Text = t"the parameter $key was not sent in the request"

trait Responder:
  def sendBody(status: Int, body: HttpBody): Unit
  def addHeader(key: Text, value: Text): Unit

object Handler:
  given [T: Show]: SimpleHandler[T] =
    SimpleHandler(t"text/plain", v => HttpBody.Chunked(LazyList(summon[Show[T]].show(v).bytes)))

  given iarrayByteHandler[T](using hr: clairvoyant.HttpResponse[T]): SimpleHandler[T] =
    SimpleHandler(Text(hr.mediaType), value => HttpBody.Chunked(hr.content(value).map { v => v }))

  given Handler[Redirect] with
    def process(content: Redirect, status: Int, headers: Map[Text, Text],
                    responder: Responder): Unit =
      responder.addHeader(ResponseHeader.Location.header, content.location.show)
      for (k, v) <- headers do responder.addHeader(k, v)
      responder.sendBody(301, HttpBody.Empty)

  given [T: SimpleHandler]: Handler[NotFound[T]] with
    def process(notFound: NotFound[T], status: Int, headers: Map[Text, Text],
                    responder: Responder): Unit =
      val handler = summon[SimpleHandler[T]]
      responder.addHeader(ResponseHeader.ContentType.header, handler.mime)
      for (k, v) <- headers do responder.addHeader(k, v)
      responder.sendBody(404, handler.stream(notFound.content))

  given [T: SimpleHandler]: Handler[ServerError[T]] with
    def process(notFound: ServerError[T], status: Int, headers: Map[Text, Text],
                    responder: Responder): Unit =
      val handler = summon[SimpleHandler[T]]
      responder.addHeader(ResponseHeader.ContentType.header, handler.mime)
      for (k, v) <- headers do responder.addHeader(k, v)
      responder.sendBody(500, handler.stream(notFound.content))

object Redirect:
  def apply[T: Locatable](location: T): Redirect =
    new Redirect(summon[Locatable[T]].location(location))

case class Redirect(location: Uri)

trait Handler[T]:
  def process(content: T, status: Int, headers: Map[Text, Text], responder: Responder): Unit

object SimpleHandler:
  def apply[T](mime: Text, stream: T => HttpBody): SimpleHandler[T] =
    new SimpleHandler(mime, stream) {}

trait SimpleHandler[T](val mime: Text, val stream: T => HttpBody) extends Handler[T]:
  def process(content: T, status: Int, headers: Map[Text, Text], responder: Responder): Unit =
    responder.addHeader(ResponseHeader.ContentType.header, mime)
    for (k, v) <- headers do responder.addHeader(k, v)
    responder.sendBody(status, stream(content))

case class NotFound[T: SimpleHandler](content: T)
case class ServerError[T: SimpleHandler](content: T)

case class Cookie(name: Text, value: Text, domain: Maybe[Text] = Unset,
                      path: Maybe[Text] = Unset, expiry: Maybe[Long] = Unset,
                      ssl: Boolean = false)

case class Response[T: Handler](content: T, status: HttpStatus = HttpStatus.Ok,
                                    headers: Map[ResponseHeader, Text] = Map(),
                                    cookies: List[Cookie] = Nil):


  private val df: jt.SimpleDateFormat = jt.SimpleDateFormat("dd MMM yyyy HH:mm:ss")

  def respond(responder: Responder): Unit =
    val cookieHeaders: List[(ResponseHeader, Text)] = cookies.map:
      cookie =>
        ResponseHeader.SetCookie -> List[(Text, Boolean | Option[Text])](
          cookie.name -> Some(cookie.value),
          t"Expires"  -> cookie.expiry.option.map { t => t"${df.format(t).nn} GMT" },
          t"Domain"   -> cookie.domain.option,
          t"Path"     -> cookie.path.option,
          t"Secure"   -> cookie.ssl,
          t"HttpOnly" -> false
        ).collect:
          case (k, true)    => k
          case (k, Some(v)) => t"$k=$v"
        .join(t"; ")
    
    summon[Handler[T]].process(content, status.code, (headers ++ cookieHeaders).map { (k, v) =>
        k.header -> v }, responder)

object Request:
  given Show[Request] = request =>
    val bodySample: Text =
      try request.body.stream.slurp(limit = 256.b).uString
      catch
        case err: ExcessDataError => t"[...]"
        case err: StreamCutError  => t"[-/-]"
    
    val headers: Text =
      request.rawHeaders.map:
        (k, vs) => t"$k: ${vs.join(t"; ")}"
      .join(t"\n          ")
    
    val params: Text = request.params.map:
      (k, v) => t"$k=\"$v\""
    .join(t"\n          ")

    ListMap[Text, Text](
      t"content"  -> request.contentType.show,
      t"method"   -> request.method.show,
      t"query"    -> request.query.show,
      t"ssl"      -> request.ssl.show,
      t"hostname" -> request.hostname.show,
      t"port"     -> request.port.show,
      t"path"     -> request.path.show,
      t"body"     -> bodySample,
      t"headers"  -> headers,
      t"params"   -> params
    ).map:
      (k, v) => t"$k = $v"
    .join(t", ")

case class Request(method: HttpMethod, body: HttpBody.Chunked, query: Text, ssl: Boolean,
                       hostname: Text, port: Int, path: Text,
                       rawHeaders: Map[Text, List[Text]],
                       queryParams: Map[Text, List[Text]]):

  // FIXME: The exception in here needs to be handled elsewhere
  val params: Map[Text, Text] =
    try
      queryParams.map:
        (k, vs) => k.urlDecode -> vs.headOption.getOrElse(t"").urlDecode
      .to(Map) ++ {
        if (method == HttpMethod.Post || method == HttpMethod.Put) &&
            (contentType == Some(media"application/x-www-form-urlencoded") || contentType == None)
        then
          Map[Text, Text](body.stream.slurp(limit = 1.mb).uString.cut(t"&").map(_.cut(t"=", 2).to(Seq) match
            case Seq(key: Text)              => key.urlDecode.show -> t""
            case Seq(key: Text, value: Text) => key.urlDecode.show -> value.urlDecode.show
            case _                         => throw Impossible("key/value pair does not match")
          )*)
        else Map[Text, Text]()
      }
    catch
      case e: ExcessDataError => Map()
      case e: StreamCutError  => Map()

  
  lazy val headers: Map[RequestHeader, List[Text]] =
    rawHeaders.map:
      case (RequestHeader(header), values) => header -> values

  lazy val length: Int =
    headers.get(RequestHeader.ContentLength)
      .map(_.head)
      .flatMap(Int.unapply(_))
      .getOrElse(body.stream.map(_.length).sum)
  
  lazy val contentType: Option[MediaType] =
    headers.get(RequestHeader.ContentType).flatMap(_.headOption).flatMap(MediaType.unapply(_))
  
trait RequestHandler:
  def listen(handler: Request ?=> Response[?])(using Log): HttpService

extension (value: Http.type)
  def listen(handler: Request ?=> Response[?])(using RequestHandler, Log): HttpService =
    summon[RequestHandler].listen(handler)

def request(using Request): Request = summon[Request]
inline def param(using Request)(key: Text): Text =
  summon[Request].params.get(key).getOrElse:
    throw MissingParamError(key)

def header(using Request)(header: RequestHeader): List[Text] =
  summon[Request].headers.get(header).getOrElse(Nil)

object ParamReader:
  given ParamReader[Int] = str => Int.unapply(str)
  given ParamReader[Text] = Some(_)

object UrlPath:
  def unapply(request: Request): Some[String] = Some(request.path.s)

trait ParamReader[T]:
  def read(value: Text): Option[T]

object RequestParam:
  given clairvoyant.HtmlAttribute["name", RequestParam[?]] with
    def name: String = "name"
    def serialize(value: RequestParam[?]): String = value.key.s

case class RequestParam[T](key: Text)(using ParamReader[T]):
  def opt(using Request): Option[T] =
    summon[Request].params.get(key).flatMap(summon[ParamReader[T]].read(_))

  def unapply(req: Request): Option[T] = opt(using req)
  def apply()(using Request): T = opt.getOrElse(throw MissingParamError(key))

trait HttpService:
  def stop(): Unit
  def await(): Unit

@targetName("Ampersand")
val `&` = Split

object Split:
  def unapply(req: Request): (Request, Request) = (req, req)

case class HttpServer(port: Int) extends RequestHandler:

  def listen(handler: Request ?=> Response[?])(using Log): HttpService =
    def handle(exchange: HttpExchange | Null) =
      try handler(using makeRequest(exchange.nn)).respond(SimpleResponder(exchange.nn))
      catch case NonFatal(exception) => exception.printStackTrace()
    
    val httpServer = JavaHttpServer.create(InetSocketAddress("localhost", port), 0).nn

    val context = httpServer.createContext("/").nn
    context.setHandler(handle(_))
    httpServer.setExecutor(null)
    httpServer.start()

    val shutdownThread = new Thread:
      override def run(): Unit =
        Log.info(t"Shutting down HTTP service on port $port")
        httpServer.stop(1)
    
    Runtime.getRuntime.nn.addShutdownHook(shutdownThread)
    
    new HttpService:
      private var continue: Boolean = true
      def stop(): Unit =
        Runtime.getRuntime.nn.removeShutdownHook(shutdownThread)
        httpServer.stop(1)
        continue = false
      
      def await(): Unit = while continue do Thread.sleep(100)

  private def streamBody(exchange: HttpExchange): HttpBody.Chunked =
    val in = exchange.getRequestBody.nn
    val buffer = new Array[Byte](65536)
    
    def recur(): DataStream =
      val len = in.read(buffer)
      if len > 0 then buffer.slice(0, len).snapshot #:: recur() else LazyList.empty
    
    HttpBody.Chunked(recur())

  private def makeRequest(exchange: HttpExchange)(using Log): Request =
    val uri = exchange.getRequestURI.nn
    val query = Option(uri.getQuery)
    
    val queryParams: Map[Text, List[Text]] = query.fold(Map()):
      query =>
        val paramStrings = query.nn.show.cut(t"&")
        
        paramStrings.foldLeft(Map[Text, List[Text]]()):
          (map, elem) =>
            val kv = elem.cut(t"=", 2)
            map.updated(kv(0), kv(1) :: map.getOrElse(kv(0), Nil))
    
    val headers = exchange.getRequestHeaders.nn.asScala.view.mapValues(_.nn.asScala.to(List)).to(Map)

    val request = Request(
      method = HttpMethod.valueOf(exchange.getRequestMethod.nn.show.lower.capitalize.s),
      body = streamBody(exchange),
      query = Text(query.getOrElse("").nn),
      ssl = false,
      Text(Option(uri.getHost).getOrElse(exchange.getLocalAddress.nn.getAddress.nn.getCanonicalHostName
          ).nn),
      Option(uri.getPort).filter(_ > 0).getOrElse(exchange.getLocalAddress.nn.getPort),
      Text(uri.getPath.nn),
      headers.map { (k, v) => Text(k) -> v.map(Text(_)) },
      queryParams
    )

    Log.fine(t"Received HTTP request $request")

    request

  class SimpleResponder(exchange: HttpExchange) extends Responder:
    def addHeader(key: Text, value: Text): Unit = exchange.getResponseHeaders.nn.add(key.s, value.s)
    
    def sendBody(status: Int, body: HttpBody): Unit =
      val length = body match
        case HttpBody.Empty      => -1
        case HttpBody.Data(body) => body.length
        case HttpBody.Chunked(_) => 0

      exchange.sendResponseHeaders(status, length)
      
      body match
        case HttpBody.Empty =>
          ()
        
        case HttpBody.Data(body) =>
          exchange.getResponseBody.nn.write(body.unsafeMutable)
        
        case HttpBody.Chunked(body) =>
          try body.map(_.unsafeMutable).foreach(exchange.getResponseBody.nn.write(_))
          catch case e: StreamCutError => () // FIXME: Should this be ignored?
      
      exchange.getResponseBody.nn.flush()
      exchange.close()

case class Svg(content: Text)

object Svg:
  given SimpleHandler[Svg] = SimpleHandler(t"image/svg+xml", svg => HttpBody.Data(svg.content.bytes))

case class Jpeg(content: IArray[Byte])

object Jpeg:
  given SimpleHandler[Jpeg] = SimpleHandler(t"image/jpeg", jpeg => HttpBody.Data(jpeg.content))

case class Gif(content: IArray[Byte])

object Gif:
  given SimpleHandler[Gif] = SimpleHandler(t"image/gif", gif => HttpBody.Data(gif.content))

case class Png(content: IArray[Byte])

object Png:
  given SimpleHandler[Png] = SimpleHandler(t"image/png", png => HttpBody.Data(png.content))

def basicAuth(validate: (Text, Text) => Boolean)(response: => Response[?])
             (using Request): Response[?] =
  request.headers.get(RequestHeader.Authorization) match
    case Some(List(s"Basic $credentials")) =>
      val Seq(username: Text, password: Text) =
        val text: Text = credentials.show.decode[Base64].uString
        text.cut(t":").to(Seq)
      
      if validate(username, password) then response else Response("", HttpStatus.Forbidden)

    case _ =>
      val auth = t"""Basic realm="Some realm", charset="UTF-8""""
      Response("", HttpStatus.Unauthorized, Map(ResponseHeader.WwwAuthenticate -> auth))
