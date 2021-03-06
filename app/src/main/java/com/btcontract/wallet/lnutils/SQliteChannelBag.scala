package com.btcontract.wallet.lnutils

import spray.json._
import com.btcontract.wallet.lnutils.ImplicitJsonFormats._
import com.btcontract.wallet.ln.{ChannelBag, HostedCommits}
import fr.acinq.bitcoin.ByteVector32


class SQliteChannelBag(db: LNOpenHelper) extends ChannelBag {
  def put(chanId: ByteVector32, data: HostedCommits): HostedCommits = {
    // Insert and then update because of INSERT IGNORE sqlite effects
    val dataJson = data.toJson.toString
    val chanIdJson = chanId.toHex

    db.change(ChannelTable.newSql, chanIdJson, dataJson)
    db.change(ChannelTable.updSql, dataJson, chanIdJson)
    data
  }

  def delete(chanId: ByteVector32): Unit = db.change(ChannelTable.killSql, chanId.toHex)

  def all: Vector[HostedCommits] = db.select(ChannelTable.selectAllSql).vec(_ string ChannelTable.data) map to[HostedCommits]
}