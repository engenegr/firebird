package com.btcontract.wallet.helper

import com.btcontract.wallet.ln.crypto.Tools.{Bytes, runAnd}
import androidx.loader.content.AsyncTaskLoader
import android.content.Context
import android.database.Cursor
import scala.util.Try


case class RichCursor(c: Cursor) extends Iterable[RichCursor] { me =>
  def set[T](trans: RichCursor => T): Set[T] = try map(trans).toSet finally c.close
  def vec[T](trans: RichCursor => T): Vector[T] = try map(trans).toVector finally c.close
  def headTry[T](fun: RichCursor => T): Try[T] = try Try(fun apply head) finally c.close
  def string(stringKey: String): String = c.getString(c getColumnIndex stringKey)
  def bytes(byteKey: String): Bytes = c.getBlob(c getColumnIndex byteKey)
  def long(longKey: String): Long = c.getLong(c getColumnIndex longKey)
  def int(intKey: String): Int = c.getInt(c getColumnIndex intKey)

  def iterator: Iterator[RichCursor] = new Iterator[RichCursor] {
    def hasNext: Boolean = c.getPosition < c.getCount - 1
    def next: RichCursor = runAnd(me)(c.moveToNext)
  }
}

// Loading data with side effect
abstract class ReactLoader[T](ct: Context)
  extends AsyncTaskLoader[Cursor](ct) {

  def loadInBackground: Cursor = {
    val cursor: Cursor = this.getCursor
    consume(RichCursor(cursor) vec createItem)
    cursor
  }

  val consume: Vector[T] => Unit
  def createItem(wrap: RichCursor): T
  def getCursor: Cursor
}