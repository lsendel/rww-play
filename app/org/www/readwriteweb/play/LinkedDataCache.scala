package org.www.readwriteweb.play

import org.w3.banana._
import org.www.play.rdf.IterateeSelector
import org.www.play.remote.{GraphNHeaders, GraphFetcher}
import java.net.{MalformedURLException, URL}
import concurrent.{ExecutionContext, Future}
import org.w3.banana.LinkedDataResource
import util.FutureValidation

trait LinkedDataCache[Rdf<:RDF] {
  def get(uri: Rdf#URI): Future[LinkedDataResource[Rdf]]
}

/**
 * fetches and caches linked data resource
 * todo: this implementation does not cache
 */
class IterateeLDCache[Rdf<:RDF](val graphSelector: IterateeSelector[Rdf#Graph])
                               (implicit dsl: Diesel[Rdf], ec: ExecutionContext)
extends LinkedDataCache[Rdf] {
  val graphFetcher = new GraphFetcher[Rdf](graphSelector)
  import dsl._
  import dsl.ops._

   def get(uri: Rdf#URI): Future[LinkedDataResource[Rdf]] = {
     try {
       val url = new URL(uri.fragmentLess.toString)
       val fvIteratee: Future[GraphNHeaders[Rdf]] =
         graphFetcher.fetch(url).flatMap { validation =>
           validation.fold(fail=>Future.failed(fail),succ=>Future.successful(succ))
         }
       fvIteratee.map { gh =>
          val resURI = URI(url.toString)
          LinkedDataResource(resURI,new PointedGraph(uri,gh.graph))
       }
     } catch {
       case e: MalformedURLException =>
         Future.failed(WrappedThrowable(e))
     }

   }
}
