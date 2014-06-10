/*
 *  DocumentHandler.scala
 *  (Mellite)
 *
 *  Copyright (c) 2012-2014 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.mellite

import impl.{DocumentHandlerImpl => Impl}
import de.sciss.model.Model
import de.sciss.lucre.{event => evt}
import language.existentials
import evt.Sys
import de.sciss.file.File

object DocumentHandler {
  type Document = Workspace[_ <: Sys[_]]

  lazy val instance: DocumentHandler = Impl()

  sealed trait Update
  final case class Opened[S <: Sys[S]](doc: Workspace[S]) extends Update
  final case class Closed[S <: Sys[S]](doc: Workspace[S]) extends Update
}
trait DocumentHandler extends Model[DocumentHandler.Update] {
  import DocumentHandler.Document

  def addDocument[S <: Sys[S]](doc: Workspace[S])(implicit tx: S#Tx): Unit

  // def openRead(path: String): Document
  def allDocuments: Iterator[Document]
  def getDocument(folder: File): Option[Document]

  def isEmpty: Boolean
}