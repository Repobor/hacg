package io.github.yueeng.hacg

import java.net._
import java.security.MessageDigest
import java.text.{ParseException, SimpleDateFormat}
import java.util
import java.util.Date
import java.util.concurrent.TimeUnit

import android.content.DialogInterface.OnDismissListener
import android.content.{Context, DialogInterface, Intent, SharedPreferences}
import android.net.Uri
import android.os.{AsyncTask, Bundle}
import android.preference.PreferenceManager
import android.support.multidex.MultiDexApplication
import android.support.v4.app.Fragment
import android.view.View
import com.squareup.okhttp.{FormEncodingBuilder, OkHttpClient, Request}
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.language.{implicitConversions, postfixOps, reflectiveCalls}
import scala.util.Random

object HAcg {
  val config = PreferenceManager.getDefaultSharedPreferences(HAcgApplication.instance)

  def HOST = config.getString("system.host", "hacg.me")

  def HOST_=(host: String) = config.edit().putString("system.host", host).commit()

  def WEB = s"http://www.$HOST"

  def WORDPRESS = s"$WEB/wordpress"

  def HOSTS = config.getStringSet("system.hosts", DEFAULT_HOSTS)

  def HOSTS_=(hosts: Set[String]) = config.edit().putStringSet("system.hosts", hosts).commit()

  val DEFAULT_HOSTS = Set("hacg.me", "hacg.be", "hacg.club")

  val release = "https://github.com/yueeng/hacg/releases"

  val philosophy = "http://www.zhexue.in/m"
}

object HAcgApplication {
  private var _instance: HAcgApplication = _

  def instance = _instance
}

class HAcgApplication extends MultiDexApplication {
  HAcgApplication._instance = this

  override def onCreate(): Unit = {
    super.onCreate()
    CookieHandler.setDefault(CookieManagerProxy.instance)
  }
}

object Common {
  implicit def viewTo[T <: View](view: View): T = view.asInstanceOf[T]

  implicit def viewClick(func: View => Unit): View.OnClickListener = new View.OnClickListener {
    override def onClick(view: View): Unit = func(view)
  }

  implicit def dialogClick(func: (DialogInterface, Int) => Unit): DialogInterface.OnClickListener = new DialogInterface.OnClickListener {
    override def onClick(dialog: DialogInterface, which: Int): Unit = func(dialog, which)
  }

  implicit def dialogDismiss(func: DialogInterface => Unit): DialogInterface.OnDismissListener = new OnDismissListener {
    override def onDismiss(dialog: DialogInterface): Unit = func(dialog)
  }

  implicit def runnable(func: () => Unit): Runnable = new Runnable {
    override def run(): Unit = func()
  }

  implicit def pair(p: (View, String)): android.support.v4.util.Pair[View, String] =
    new android.support.v4.util.Pair[View, String](p._1, p._2)

  implicit class fragmentex(f: Fragment) {
    def arguments(b: Bundle) = {
      f.setArguments(b)
      f
    }
  }

  implicit class bundleex(b: Bundle) {
    def string(key: String, value: String) = {
      b.putString(key, value)
      b
    }
  }

  implicit class StringUtil(s: String) {
    def isNullOrEmpty = s == null || s.isEmpty

    def isNonEmpty = !isNullOrEmpty
  }

  private val datefmt = new SimpleDateFormat("yyyy-MM-dd'T'hh:mm:ssZZZZZ")

  implicit def string2date(str: String): Option[Date] = {
    try {
      Option(datefmt.parse(str))
    } catch {
      case _: ParseException => None
    }
  }

  implicit def date2long(date: Date): Long = date.getTime

  implicit def date2string(date: Date): String = datefmt.format(date)

  def using[A, B <: {def close() : Unit}](closeable: B)(f: B => A): A =
    try {
      f(closeable)
    } finally {
      closeable.close()
    }

  implicit class ReduceMap[T](list: List[T]) {
    def reduceMap(func: T => List[T]): List[T] = {
      list match {
        case Nil => Nil
        case head :: tail => head :: func(head).reduceMap(func) ::: tail.reduceMap(func)
      }
    }
  }

  implicit class digest2string(s: String) {
    def md5 = MessageDigest.getInstance("MD5").digest(s.getBytes).map("%02X".format(_)).mkString

    def sha1 = MessageDigest.getInstance("SHA1").digest(s.getBytes).map("%02X".format(_)).mkString
  }

  private val img = List(".jpg", ".png", ".webp")

  implicit class httpex(url: String) {
    def isImg = img.exists(url.toLowerCase.endsWith)

    def httpGet = {
      try {
        val http = new OkHttpClient()
        http.setConnectTimeout(15, TimeUnit.SECONDS)
        http.setReadTimeout(30, TimeUnit.SECONDS)
        val request = new Request.Builder().get().url(url).build()
        val response = http.newCall(request).execute()
        Option(response.body().string(), response.request().urlString())
      } catch {
        case _: Exception => None
      }
    }

    def httpPost(post: Map[String, String]) = {
      try {
        val http = new OkHttpClient()
        http.setConnectTimeout(15, TimeUnit.SECONDS)
        http.setWriteTimeout(30, TimeUnit.SECONDS)
        http.setReadTimeout(30, TimeUnit.SECONDS)
        val data = (new FormEncodingBuilder /: post)((b, o) => b.add(o._1, o._2)).build()
        val request = new Request.Builder().url(url).post(data).build()
        val response = http.newCall(request).execute()
        Option(response.body().string(), response.request().urlString())
      } catch {
        case _: Exception => None
      }
    }
  }

  implicit class jsoupex(html: Option[(String, String)]) {
    def jsoup = html match {
      case Some(h) => Option(Jsoup.parse(h._1, h._2))
      case _ => None
    }

    def jsoup[T](f: Document => T): Option[T] = {
      html.jsoup match {
        case Some(h) => Option(f(h))
        case _ => None
      }
    }
  }

  def version(context: Context): String = {
    try context.getPackageManager.getPackageInfo(context.getPackageName, 0).versionName catch {
      case e: Exception => e.printStackTrace(); ""
    }
  }

  def versionBefore(local: String, online: String): Boolean = {
    try {
      val l = local.split( """\.""").map(_.toInt).toList
      val o = online.split( """\.""").map(_.toInt).toList
      for (i <- 0 until Math.min(l.length, o.length)) {
        if (l(i) < o(i)) {
          return true
        }
      }
      if (o.length > l.length) {
        return o.drop(l.length).exists(_ != 0)
      }
    } catch {
      case e: Exception =>
    }
    false
  }

  def openWeb(context: Context, uri: String) =
    context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

  val random = new Random(System.currentTimeMillis())

  def randomColor(alpha: Int = 0xFF) = android.graphics.Color.HSVToColor(alpha, Array[Float](random.nextInt(360), 1, 0.5F))
}

abstract class ScalaTask[A, P, R] extends AsyncTask[A, P, R] {
  final override def doInBackground(params: A*): R = background(params: _*)

  final override def onPreExecute(): Unit = super.onPreExecute()

  final override def onPostExecute(result: R): Unit = post(result)

  final override def onProgressUpdate(values: P*): Unit = progress(values: _*)

  def pre(): Unit = {}

  def post(result: R): Unit = {}

  def progress(values: P*): Unit = {}

  def background(params: A*): R
}

class CookieManagerProxy(store: CookieStore, policy: CookiePolicy) extends CookieManager(store, policy) {

  private val SetCookie: String = "Set-Cookie"

  override def put(uri: URI, headers: util.Map[String, util.List[String]]): Unit = {
    super.put(uri, headers)
    getCookieStore.get(uri)
      .filter(o => o.getDomain != null && !o.getDomain.startsWith("."))
      .foreach(o => o.setDomain(s".${o.getDomain}"))
  }

  def put(uri: URI, cookies: String): Unit = cookies match {
    case null =>
    case _ =>
      this.put(uri, Map[String, util.List[String]](SetCookie -> cookies.split(";").map(_.trim).toList))
  }
}

object CookieManagerProxy {
  val instance = new CookieManagerProxy(new PersistCookieStore(HAcgApplication.instance), CookiePolicy.ACCEPT_ALL)
}

/**
 * PersistentCookieStore
 * Created by Rain on 2015/7/1.
 */
class PersistCookieStore(context: Context) extends CookieStore {
  private final val map = new mutable.HashMap[URI, mutable.HashSet[HttpCookie]]
  private final val pref: SharedPreferences = context.getSharedPreferences("cookies.pref", Context.MODE_PRIVATE)

  pref.getAll.collect { case (k: String, v: String) if !v.isEmpty => (k, v.split(",")) }
    .foreach { o =>
    map(URI.create(o._1)) = mutable.HashSet() ++= o._2.flatMap {
      c => try HttpCookie.parse(c) catch {
        case _: Throwable => Nil
      }
    }
  }

  implicit class httpCookie(c: HttpCookie) {
    def string: String = {
      if (c.getVersion != 0) {
        return c.toString
      }
      Map(c.getName -> c.getValue, "domain" -> c.getDomain)
        .view
        .filter(_._2 != null)
        .filter(!_._2.isEmpty)
        .map(o => s"${o._1}=${o._2}")
        .mkString("; ")
    }
  }

  private def cookiesUri(uri: URI): URI = {
    if (uri == null) {
      return null
    }
    try new URI("http", uri.getHost, null, null) catch {
      case e: URISyntaxException => uri
    }
  }

  override def add(url: URI, cookie: HttpCookie): Unit = map.synchronized {
    if (cookie == null) {
      throw new NullPointerException("cookie == null")
    }
    cookie.getDomain match {
      case domain if !domain.startsWith(".") => cookie.setDomain(s".$domain")
      case _ =>
    }

    val uri = cookiesUri(url)

    if (map.contains(uri)) {
      map(uri) += cookie
    } else {
      map(uri) = new mutable.HashSet() += cookie
    }

    pref.edit.putString(uri.toString, map(uri).map(_.string).toSet.mkString(",")).apply()
  }

  def remove(url: URI, cookie: HttpCookie): Boolean = map.synchronized {
    if (cookie == null) {
      throw new NullPointerException("cookie == null")
    }
    val uri = cookiesUri(url)
    map.get(uri) match {
      case Some(cookies) if cookies.contains(cookie) =>
        cookies.remove(cookie)
        pref.edit.putString(uri.toString, cookies.map(_.string).toSet.mkString(",")).apply()
        true
      case _ => false
    }
  }

  override def removeAll(): Boolean = map.synchronized {
    val result = map.nonEmpty
    map.clear()
    pref.edit.clear.apply()
    result
  }

  override def getURIs: util.List[URI] = map.synchronized {
    map.keySet.filter(o => o != null).toList
  }

  def expire(uri: URI, cookies: mutable.HashSet[HttpCookie], edit: SharedPreferences.Editor)(fn: HttpCookie => Boolean = c => true) = {
    cookies.filter(fn).filter(_.hasExpired) match {
      case ex if ex.nonEmpty =>
        cookies --= ex
        edit.putString(uri.toString, cookies.map(_.string).toSet.mkString(",")).apply()
      case _ =>
    }
  }

  override def getCookies: util.List[HttpCookie] = map.synchronized {
    (pref.edit() /: map) { (e, o) => expire(o._1, o._2, e)(); e }.apply()
    map.values.flatten.toList.distinct
  }

  override def get(uri: URI): util.List[HttpCookie] = map.synchronized {
    if (uri == null) {
      throw new NullPointerException("uri == null")
    }
    val edit = pref.edit()
    map.get(uri) match {
      case Some(cookies) => expire(uri, cookies, edit)()
      case _ =>
    }
    map.filter(_._1 != uri).foreach {
      o => expire(o._1, o._2, edit)(c => HttpCookie.domainMatches(c.getDomain, uri.getHost))
    }
    edit.apply()
    (map.getOrElse(uri, Nil) ++ map.values.flatMap(o => o.filter(c => HttpCookie.domainMatches(c.getDomain, uri.getHost)))).toList.distinct
  }
}