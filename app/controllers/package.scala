package controllers

import _root_.play.api.Logger
import _root_.play.api.Play
import akka.actor.{Props, ActorSystem}
import concurrent.ExecutionContext
import rww.ldp._
import java.io.File
import java.nio.file.Path
import akka.util.Timeout
import java.util.concurrent.{Executors, ExecutorService, TimeUnit}
import rww.ldp.actor._
import rww.ldp.actor.plantain.{LDPCSubdomainActor, LDPCActor}

import java.net.URL
import rww.play.rdf.plantain.{PlantainSparqlUpdateIteratee, PlantainSparqlQueryIteratee, PlantainBlockingRDFIteratee}


import org.w3.banana._
import org.w3.banana.plantain._
import rww.play.rdf.IterateeSelector
import rww.ldp.actor.remote.LDPWebActor
import scala.util.Try


/**
 * gather some common setup values
 **/
trait Setup {

  implicit val system = ActorSystem("MySystem")
  implicit val executionContext: ExecutionContext = system.dispatcher

  val logger = Logger("rww")
  val httpsPortKey = "https.port"
  val httpHostnameKey = "http.hostname"
  val RootContainerPathKey = "rww.root.container.path"
  val rwwSubDomainsEnabledKey = "rww.subdomains"
  val baseHostnameKey = "http.hostname"

  //Play setup: needed for WebID info
  //todo: the code below should be adapted to finding the real default of Play.
  lazy val securePort: Option[Int] = Play.current.configuration.getInt(httpsPortKey)

  lazy val port: Int = Play.current.configuration.getInt(httpsPortKey).orElse(securePort).get

  lazy val host: String =
    Play.current.configuration.getString(httpHostnameKey).getOrElse("localhost")


  lazy val hostRoot: URL = {
    val protocol = if (securePort==None) "http" else "https"
    new URL(protocol,host,port,"")
  }

  def hostRootSubdomain(subdomain: String): URL = {
    val subdomainHost = subdomain + "." + hostRoot.getHost
    new URL(hostRoot.getProtocol,subdomainHost,hostRoot.getPort,hostRoot.getFile)
  }

  lazy val rwwRoot: URL =  {
    val path = controllers.routes.MainController.about.url+"/" // TODO: not the appropriate way to get this url!
    new URL(hostRoot,path)
  }

  lazy val rwwSubdomainsEnabled: Boolean = Play.current.configuration.getBoolean(rwwSubDomainsEnabledKey).getOrElse(false)

  /**
   * we check the existence of the file because Resource.fromFile creates the file if it doesn't exist
   * (the doc says it raises an exception but it's not the case)
   * @param key
   * @return
   */
  def getFileForConfigurationKey(key: String): File = {
    val path = Play.current.configuration.getString(key)
    require(path.isDefined,s"Missing configuration for key $key")
    val file = new File(path.get)
    require(file.exists() && file.canRead,s"Unable to find or read file/directory: $file")
    file
  }


  val rootContainerPath: Path = {
    val file = getFileForConfigurationKey(RootContainerPathKey)
    require(file.isDirectory,s"The root container ($file) is not a directory")
    file.toPath.toAbsolutePath
  }


  logger.info(s""""
    secure port=$securePort
    port = $port
    host = $host
    hostRoot = $hostRoot
    rwwRoot = $rwwRoot
    rww root LDPC path = $rootContainerPath
    with subdomain support = $rwwSubdomainsEnabled
    """)
}


object plantain extends Setup {
  type Rdf = Plantain

  implicit val ops = Plantain.ops
  implicit val sparqlOps = Plantain.sparqlOps
  val blockingIteratee = new PlantainBlockingRDFIteratee
  implicit val writerSelector : RDFWriterSelector[Rdf] =
     RDFWriterSelector[Rdf, Turtle] combineWith RDFWriterSelector[Rdf, RDFXML]

  implicit val solutionsWriterSelector = Plantain.solutionsWriterSelector
  implicit val patch: LDPatch[Rdf,Try] = PlantainLDPatch
  implicit val timeout = Timeout(30,TimeUnit.SECONDS)

  //we don't have an iteratee selector for Plantain
  implicit val iterateeSelector: IterateeSelector[Rdf#Graph] = blockingIteratee.BlockingIterateeSelector
  implicit val sparqlSelector:  IterateeSelector[Rdf#Query] =  PlantainSparqlQueryIteratee.sparqlSelector
  implicit val sparqlupdateSelector:  IterateeSelector[Rdf#UpdateQuery] =  PlantainSparqlUpdateIteratee.sparqlSelector
  implicit val webClient: WebClient[Plantain] = new WSClient(Plantain.readerSelector,Plantain.turtleWriter)

  val rww: RWWActorSystem[Plantain] = {
    val rootURI = ops.URI(rwwRoot.toString)
    if (plantain.rwwSubdomainsEnabled) RWWActorSystemImpl.withSubdomains[Plantain](rootURI, rootContainerPath, webClient)
    else RWWActorSystemImpl.withSubdomains[Plantain](rootURI, rootContainerPath, webClient)
  }


}

