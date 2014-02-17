/*
 *  Prefs.scala
 *  (Mellite)
 *
 *  Copyright (c) 2012-2014 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.mellite

import de.sciss.desktop.Preferences.Entry
import de.sciss.file._

object Prefs {
  import Mellite.userPrefs

  final val defaultSuperCollider    = file("<SC_HOME>")
  final val defaultAudioDevice      = "<default>"
  final val defaultAudioNumOutputs  = 8
  final val defaultHeadphonesBus    = 0

  def superCollider  : Entry[File  ] = userPrefs[File  ]("supercollider")
  def audioDevice    : Entry[String] = userPrefs[String]("audio-device")
  def audioNumOutputs: Entry[Int   ] = userPrefs[Int   ]("audio-num-outputs")
  def headphonesBus  : Entry[Int   ] = userPrefs[Int   ]("headphones-bus")
}