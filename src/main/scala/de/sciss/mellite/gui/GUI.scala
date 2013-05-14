package de.sciss
package mellite
package gui

import scala.swing.Swing._
import scalaswingcontrib.group.GroupPanel
import scala.swing.{Window, Swing, Dialog, Component, TextField, Label, Alignment}
import java.awt.{Rectangle, EventQueue, GraphicsEnvironment}
import javax.swing.Timer

// XXX TODO: this stuff should go somewhere for re-use.
object GUI {
  def centerOnScreen(w: desktop.Window) {
    placeWindow(w, 0.5f, 0.5f, 0)
  }

  def delay(millis: Int)(block: => Unit) {
    val timer = new Timer(millis, Swing.ActionListener(_ => block))
    timer.setRepeats(false)
    timer.start()
  }

  def fixWidth(c: Component, width: Int = -1) {
    val w         = if (width < 0) c.preferredSize.width else width
    val min       = c.minimumSize
    val max       = c.maximumSize
    min.width     = w
    max.width     = w
    c.minimumSize = min
    c.maximumSize = max
  }

  def findWindow(c: Component): Option[desktop.Window] = {
    None  // XXX TODO - we should place a client property in Desktop
  }

  def maximumWindowBounds: Rectangle = {
    val ge  = GraphicsEnvironment.getLocalGraphicsEnvironment
    ge.getMaximumWindowBounds
  }

  def placeWindow(w: desktop.Window, horizontal: Float, vertical: Float, padding: Int) {
    val bs  = maximumWindowBounds
    val b   = w.size
    val x   = (horizontal * (bs.width  - padding * 2 - b.width )).toInt + bs.x + padding
    val y   = (vertical   * (bs.height - padding * 2 - b.height)).toInt + bs.y + padding
    w.location = (x, y)
  }

  def requireEDT() { require(EventQueue.isDispatchThread) }

  def defer(thunk: => Unit) {
    if (EventQueue.isDispatchThread) thunk else Swing.onEDT(thunk)
  }

  def keyValueDialog(value: Component, title: String = "New Entry", defaultName: String = "Name",
                     window: Option[desktop.Window] = None): Option[String] = {
    val ggName  = new TextField(10)
    ggName.text = defaultName

    import language.reflectiveCalls // why does GroupPanel need reflective calls?
    // import desktop.Implicits._
    val box = new GroupPanel {
      val lbName  = new Label( "Name:", EmptyIcon, Alignment.Right)
      val lbValue = new Label("Value:", EmptyIcon, Alignment.Right)
      theHorizontalLayout is Sequential(Parallel(Trailing)(lbName, lbValue), Parallel(ggName, value))
      theVerticalLayout   is Sequential(Parallel(Baseline)(lbName, ggName ), Parallel(Baseline)(lbValue, value))
    }

    val pane = desktop.OptionPane.confirmation(box, optionType = Dialog.Options.OkCancel,
      messageType = Dialog.Message.Question, focus = Some(value))
    pane.title  = title
    val res = pane.show(window)

    if (res == Dialog.Result.Ok) {
      val name    = ggName.text
      Some(name)
    } else {
      None
    }
  }
}