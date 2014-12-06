/*
 *  FolderFrameImpl.scala
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
package gui
package impl
package document

import javax.swing.SpinnerNumberModel
import javax.swing.undo.UndoableEdit

import de.sciss.desktop
import de.sciss.desktop.edit.CompoundEdit
import de.sciss.desktop.impl.UndoManagerImpl
import de.sciss.desktop.{Desktop, KeyStrokes, Menu, UndoManager}
import de.sciss.file._
import de.sciss.lucre.stm
import de.sciss.lucre.swing.deferTx
import de.sciss.lucre.synth.Sys
import de.sciss.lucre.expr.{String => StringEx}
import de.sciss.mellite.gui.edit.{EditFolderInsertObj, EditFolderRemoveObj}
import de.sciss.mellite.gui.impl.component.CollectionViewImpl
import de.sciss.swingplus.{Spinner, GroupPanel}
import de.sciss.synth.proc
import de.sciss.synth.proc.{ExprImplicits, ObjKeys, StringElem, Obj, Folder}

import scala.swing.Swing.EmptyIcon
import scala.swing.{CheckBox, Swing, Alignment, Label, TextField, Dialog, Action}
import scala.collection.breakOut
import scala.swing.event.Key

object FolderFrameImpl {
  def apply[S <: Sys[S]](name: ExprView[S#Tx, String],
                         folder: Folder[S],
                         isWorkspaceRoot: Boolean)(implicit tx: S#Tx,
                         workspace: Workspace[S], cursor: stm.Cursor[S]): FolderFrame[S] = {
    implicit val undoMgr  = new UndoManagerImpl
    val folderView      = FolderView(folder)
    val interceptQuit   = isWorkspaceRoot && workspace.folder.isEmpty
    val view            = new ViewImpl[S](folderView)
    view.init()

    val res = new FrameImpl[S](view, name = name, isWorkspaceRoot = isWorkspaceRoot, interceptQuit = interceptQuit)
    res.init()
    res
  }

  def addDuplicateAction[S <: Sys[S]](w: WindowImpl[S], action: Action): Unit =
    Application.windowHandler.menuFactory.get("edit") match {
      case Some(mEdit: Menu.Group) =>
        val itDup = Menu.Item("duplicate", action)
        mEdit.add(Some(w.window), itDup)    // XXX TODO - should be insert-after "Select All"
      case _ =>
    }

  private final class FrameImpl[S <: Sys[S]](val view: ViewImpl[S], name: ExprView[S#Tx, String],
                                             isWorkspaceRoot: Boolean, interceptQuit: Boolean)
    extends WindowImpl[S](name) with FolderFrame[S] {

    def workspace = view.workspace

    def folderView = view.peer

    private var quitAcceptor = Option.empty[() => Boolean]

    override protected def initGUI(): Unit = {
      addDuplicateAction(this, view.actionDuplicate)
      if (interceptQuit) quitAcceptor = Some(Desktop.addQuitAcceptor(checkClose()))
    }

    override protected def placement: (Float, Float, Int) = (0.5f, 0.0f, 20)

    override protected def checkClose(): Boolean = !interceptQuit || {
      val msg = "<html><body>Closing an in-memory workspace means<br>" +
        "all contents will be <b>irrevocably lost</b>.<br>" +
        "<p>Ok to proceed?</body></html>"
      val opt = desktop.OptionPane.confirmation(message = msg, messageType = desktop.OptionPane.Message.Warning,
        optionType = desktop.OptionPane.Options.OkCancel)
      opt.show(Some(window), "Close Workspace") == desktop.OptionPane.Result.Ok
    }

    override protected def performClose(): Unit = {
      quitAcceptor.foreach(Desktop.removeQuitAcceptor)
      if (isWorkspaceRoot) {
        log(s"Closing workspace ${workspace.folder}")
        Application.documentHandler.removeDocument(workspace)
        workspace.close()
      } else {
        super.performClose()
      }
    }
  }

  final class ViewImpl[S <: Sys[S]](val peer: FolderView[S])
                                   (implicit val workspace: Workspace[S],
                                    val cursor: stm.Cursor[S], val undoManager: UndoManager)
    extends CollectionViewImpl[S]
    {

    impl =>

    //    protected def mkTitle(sOpt: Option[String]): String =
    //      s"${workspace.folder.base}${sOpt.fold("")(s => s"/$s")} : Elements"

    protected type InsertConfig = Unit

    protected def prepareInsert(f: ObjView.Factory): Option[InsertConfig] = Some(())

    protected def editInsert(f: ObjView.Factory, xs: List[Obj[S]], config: InsertConfig)
                            (implicit tx: S#Tx): Option[UndoableEdit] = {
      val (parent, idx) = impl.peer.insertionPoint
      val edits: List[UndoableEdit] = xs.zipWithIndex.map { case (x, j) =>
        EditFolderInsertObj(f.prefix, parent, idx + j, x)
      } (breakOut)
      CompoundEdit(edits, "Create Objects")
    }

    def dispose()(implicit tx: S#Tx): Unit =
      peer.dispose()

    final protected lazy val actionDelete: Action = Action(null) {
      val sel = peer.selection
      val edits: List[UndoableEdit] = cursor.step { implicit tx =>
        sel.flatMap { nodeView =>
          val parent = nodeView.parent
          val childH  = nodeView.modelData
          val child   = childH()
          val idx     = parent.indexOf(child)
          if (idx < 0) {
            println("WARNING: Parent folder of object not found")
            None
          } else {
            implicit val folderSer = Folder.serializer[S]
            val edit = EditFolderRemoveObj[S](nodeView.renderData.prefix, parent, idx, child)
            Some(edit)
          }
        }
      }
      val ceOpt = CompoundEdit(edits, "Delete Elements")
      ceOpt.foreach(undoManager.add)
    }

    final protected def initGUI2(): Unit = {
      peer.addListener {
        case FolderView.SelectionChanged(_, sel) =>
          selectionChanged(sel.map(_.renderData))
          actionDuplicate.enabled = sel.nonEmpty
      }
    }

    final lazy val actionDuplicate: Action = new Action("Duplicate...") {
      accelerator = Some(KeyStrokes.menu1 + Key.D)
      enabled     = impl.peer.selection.nonEmpty

      private var appendText  = "-1"
      private var count       = 1
      private var append      = true

      private def incLast(x: String, c: Int): String = {
        val p = "\\d+".r
        val m = p.pattern.matcher(x)
        var start = -1
        var end   = -1
        while (m.find()) {
          start = m.start()
          end   = m.end()
        }
        if (start < 0) x else {
          val (pre, tmp) = x.splitAt(start)
          val (old, suf) = tmp.splitAt(end - start)
          s"$pre${old.toInt + c}$suf"
        }
      }

      def apply(): Unit = {
        val sel     = impl.peer.selection
        val numSel  = sel.size
        if (numSel == 0) return

        sel.map(view => view.renderData.name)

        val txtInfo = s"Duplicate ${if (numSel == 1) s"'${sel.head.renderData.name}'" else s"$numSel Objects"}"
        val lbInfo  = new Label(txtInfo)
        lbInfo.border = Swing.EmptyBorder(0, 0, 8, 0)
        val ggName  = new TextField(6)
        ggName.text = appendText
        val mCount  = new SpinnerNumberModel(count, 1, 0x4000, 1)
        val ggCount = new Spinner(mCount)
        val lbName  = new CheckBox("Append to Name:")
        lbName.selected = append
        val lbCount = new Label("Number of Copies:" , EmptyIcon, Alignment.Right)

        val box = new GroupPanel {
          horizontal  = Par(
            lbInfo,
            Seq(Par(Trailing)(lbCount, lbName), Par(ggName , ggCount))
          )
          vertical = Seq(lbInfo, Par(Baseline)(lbName, ggName ), Par(Baseline)(lbCount, ggCount))
        }

        val pane = desktop.OptionPane.confirmation(box, optionType = Dialog.Options.OkCancel,
          messageType = Dialog.Message.Question, focus = Some(ggCount))
        pane.title  = "Duplicate"
        val window  = GUI.findWindow(component)
        val res     = pane.show(window)

        if (res == Dialog.Result.Ok) {
          // save state
          count       = mCount.getNumber.intValue()
          appendText  = ggName.text
          append      = lbName.selected

          // lazy val regex = "\\d+".r // do _not_ catch leading minus character

          val edits: List[UndoableEdit] = cursor.step { implicit tx =>
            sel.flatMap { nodeView =>
              val p       = nodeView.parent
              val orig    = nodeView.modelData()
              val idx     = p.indexOf(orig)
              val copies  = List.tabulate(count) { n =>
                val cpy = Obj.copy(orig)
                if (append) {
                  val suffix = incLast(appendText, n)
                  import proc.Implicits._
                  orig.attr[StringElem](ObjKeys.attrName).foreach { oldName =>
                    val imp = ExprImplicits[S]
                    import imp._
                    val newName = oldName ++ suffix
                    cpy.attr.put(ObjKeys.attrName, Obj(StringElem(StringEx.newVar(newName))))
                  }
                  // cpy.attr.name = s"${cpy.attr.name}$suffix"
                }
                EditFolderInsertObj("Copy", p, idx + n + 1, cpy)
              }
              copies
            }
          }
          val editOpt = CompoundEdit(edits, txtInfo)
          editOpt.foreach(undoManager.add)
        }
      }
    }

    def selectedObjects: List[ObjView[S]] = peer.selection.map(_.renderData)
  }
}