package de.sciss.mellite
package gui
package impl

import de.sciss.audiowidgets.{LCDPanel, LCDColors, LCDFont, AxisFormat}
import scala.swing.{Swing, Orientation, BoxPanel, Component, Label}
import de.sciss.lucre.event.Change
import java.awt.Graphics2D
import Swing._

final class TimeDisplayImpl(model: TimelineModel) extends TimeDisplay {
  private val lcdFormat = AxisFormat.Time(hours = true, millis = true)
  private val lcd       = new Label with DynamicComponentImpl {
    protected def component: Component = this

    private def updateText(frame: Long) {
      val secs = frame / model.sampleRate
      text = lcdFormat.format(secs, decimals = 3, pad = 12)
    }

    private val tlmListener: TimelineModel.Listener = {
      case TimelineModel.Position(_, Change(_, frame)) =>
        updateText(frame)
    }

    protected def componentShown() {
      model.addListener(tlmListener)
      updateText(model.position)
    }

    protected def componentHidden() {
      model.removeListener(tlmListener)
    }

    override protected def paintComponent(g2: Graphics2D) {
      val atOrig  = g2.getTransform
      try {
        // stupid lcd font has wrong ascent
        g2.translate(0, 3)
        // g2.setColor(java.awt.Color.red)
        // g2.fillRect(0, 0, 100, 100)
        super.paintComponent(g2)
      } finally {
        g2.setTransform(atOrig)
      }
    }

    font        = LCDFont().deriveFont(11f)
    foreground  = LCDColors.defaultFg
    text        = lcdFormat.format(0.0, decimals = 3, pad = 12)

    maximumSize = preferredSize
    minimumSize = preferredSize
  }
  //      lcd.setMinimumSize(lcd.getPreferredSize)
  //      lcd.setMaximumSize(lcd.getPreferredSize)
  private val lcdFrame  = new LCDPanel {
    contents   += lcd
    maximumSize = preferredSize
    minimumSize = preferredSize
  }
  private val lcdPane = new BoxPanel(Orientation.Vertical) {
    contents += VGlue
    contents += lcdFrame
    contents += VGlue
  }

  def component: Component = lcdPane
}