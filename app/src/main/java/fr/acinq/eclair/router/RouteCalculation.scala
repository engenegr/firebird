/*
 * Copyright 2020 ACINQ SAS
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

package fr.acinq.eclair.router

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, ByteVector64}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.router.Graph.GraphStructure.DirectedGraph.graphEdgeToHop
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Graph.{RichWeight, WeightRatios}
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.wire.ChannelUpdate
import fr.acinq.eclair._

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object RouteCalculation {
  def handleRouteRequest(graph: DirectedGraph, routerConf: RouterConf, currentBlockHeight: Long, r: RouteRequest): RouteResponse =
    findRouteInternal(graph, r.source, r.target, r.amount, r.maxFee, r.ignoreChannels, r.ignoreNodes, r.routeParams, currentBlockHeight) match {
      case Some(path) => RouteFound(r.paymentHash, r.partId, Route(path.weight.costs, path.path.map(graphEdgeToHop)))
      case _ => NoRouteAvailable(r.paymentHash, r.partId)
    }

  def makeExtraEdges(assistedRoutes: Seq[Seq[ExtraHop]], source: PublicKey, target: PublicKey): Set[GraphEdge] = {
    // we convert extra routing info provided in the payment request to fake channel_update
    val assistedChannels: Map[ShortChannelId, AssistedChannel] = assistedRoutes.flatMap(toAssistedChannels(_, target))
      .filterNot { case (_, ac) => ac.extraHop.nodeId == source } // we ignore routing hints for our own channels, we have more accurate information
      .toMap
    assistedChannels.values.map(ac =>
      GraphEdge(ChannelDesc(ac.extraHop.shortChannelId, ac.extraHop.nodeId, ac.nextNodeId), toFakeUpdate(ac.extraHop, ac.htlcMaximum))
    ).toSet
  }

  def toFakeUpdate(extraHop: ExtraHop, htlcMaximum: MilliSatoshi): ChannelUpdate = {
    // the `direction` bit in flags will not be accurate but it doesn't matter because it is not used
    // what matters is that the `disable` bit is 0 so that this update doesn't get filtered out
    ChannelUpdate(signature = ByteVector64.Zeroes, chainHash = ByteVector32.Zeroes, extraHop.shortChannelId, System.currentTimeMillis.milliseconds.toSeconds, messageFlags = 1,
      channelFlags = 0, extraHop.cltvExpiryDelta, htlcMinimumMsat = 0.msat, extraHop.feeBase, extraHop.feeProportionalMillionths, Some(htlcMaximum))
  }

  private def toAssistedChannels(extraRoute: Seq[ExtraHop], targetNodeId: PublicKey): Map[ShortChannelId, AssistedChannel] = {
    // BOLT 11: "For each entry, the pubkey is the node ID of the start of the channel", and the last node is the destination
    val assumedCapacity = MilliSatoshi(Long.MaxValue)
    val nextNodeIds = extraRoute.map(_.nodeId).drop(1) :+ targetNodeId
    extraRoute.zip(nextNodeIds).reverse.foldLeft(Map.empty[ShortChannelId, AssistedChannel]) {
      case (acs, (extraHop: ExtraHop, nextNodeId)) =>
        acs + (extraHop.shortChannelId -> AssistedChannel(extraHop, nextNodeId, assumedCapacity))
    }
  }

  /** https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#clarifications */
  val ROUTE_MAX_LENGTH = 20

  /** Max allowed CLTV for a route (two weeks) */
  val DEFAULT_ROUTE_MAX_CLTV = CltvExpiryDelta(2016)

  def getDefaultRouteParams(routerConf: RouterConf): RouteParams = RouteParams(
    maxFeeBase = routerConf.searchMaxFeeBase,
    maxFeePct = routerConf.searchMaxFeePct,
    routeMaxLength = routerConf.firstPassMaxRouteLength,
    routeMaxCltv = routerConf.firstPassMaxCltv,
    ratios = WeightRatios(
      cltvDeltaFactor = routerConf.searchRatioCltv,
      ageFactor = routerConf.searchRatioChannelAge,
      capacityFactor = routerConf.searchRatioChannelCapacity,
      successScoreFactor = routerConf.searchRatioSuccessScore
    )
  )

  @tailrec
  private def findRouteInternal(g: DirectedGraph,
                                localNodeId: PublicKey,
                                targetNodeId: PublicKey,
                                amount: MilliSatoshi,
                                maxFee: MilliSatoshi,
                                ignoredEdges: Set[ChannelDesc] = Set.empty,
                                ignoredVertices: Set[PublicKey] = Set.empty,
                                routeParams: RouteParams,
                                currentBlockHeight: Long): Option[Graph.WeightedPath] = {

    def feeOk(fee: MilliSatoshi): Boolean = fee <= maxFee

    def lengthOk(length: Int): Boolean = length <= routeParams.routeMaxLength && length <= ROUTE_MAX_LENGTH

    def cltvOk(cltv: CltvExpiryDelta): Boolean = cltv <= routeParams.routeMaxCltv

    val boundaries: RichWeight => Boolean = { weight => feeOk(weight.costs.head - amount) && lengthOk(weight.length) && cltvOk(weight.cltv) }

    val res = Graph.bestPath(g, localNodeId, targetNodeId, amount, ignoredEdges, ignoredVertices, routeParams.ratios, currentBlockHeight, boundaries)

    if (res.isEmpty && routeParams.routeMaxLength < ROUTE_MAX_LENGTH) {
      // if route not found within the constraints we relax and repeat the search
      val relaxedRouteParams = routeParams.copy(routeMaxLength = ROUTE_MAX_LENGTH, routeMaxCltv = DEFAULT_ROUTE_MAX_CLTV)
      findRouteInternal(g, localNodeId, targetNodeId, amount, maxFee, ignoredEdges, ignoredVertices, relaxedRouteParams, currentBlockHeight)
    } else {
      res
    }
  }
}
