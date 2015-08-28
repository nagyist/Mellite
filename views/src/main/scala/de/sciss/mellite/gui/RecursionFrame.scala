///*
// *  RecursionFrame.scala
// *  (Mellite)
// *
// *  Copyright (c) 2012-2015 Hanns Holger Rutz. All rights reserved.
// *
// *  This software is published under the GNU General Public License v3+
// *
// *
// *  For further information, please contact Hanns Holger Rutz at
// *  contact@sciss.de
// */
//
//package de.sciss
//package mellite
//package gui
//
//import de.sciss.lucre.stm
//import de.sciss.lucre.synth.Sys
//import de.sciss.mellite.gui.impl.{RecursionFrameImpl => Impl}
//import de.sciss.synth.proc.Code
//
//object RecursionFrame {
//  def apply[S <: Sys[S]](obj: Obj.T[S, Recursion.Elem])
//                        (implicit tx: S#Tx, workspace: Workspace[S], cursor: stm.Cursor[S],
//                         compiler: Code.Compiler): RecursionFrame[S] =
//    Impl(obj)
//}
//
//trait RecursionFrame[S <: Sys[S]] extends lucre.swing.Window[S] /* View[S] */ {
//  def window   : desktop.Window
//  // def document : Workspace[S]
//  def view : ViewHasWorkspace[S]
//}
