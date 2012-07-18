/*
 *  Document.scala
 *  (Mellite)
 *
 *  Copyright (c) 2012 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either
 *  version 2, june 1991 of the License, or (at your option) any later version.
 *
 *  This software is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *  General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public
 *  License (gpl.txt) along with this software; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.mellite

import java.io.File
import de.sciss.lucre.expr.{BiGroup, LinkedList}
import de.sciss.synth.proc.Proc
import impl.{DocumentImpl => Impl}
import de.sciss.lucre.stm.{Cursor, Sys}

object Document {
   type Group[        S <: Sys[ S ]]   = BiGroup.Modifiable[    S, Proc[ S ],  Proc.Update[ S ]]
   type GroupUpdate[  S <: Sys[ S ]]   = BiGroup.Update[        S, Proc[ S ],  Proc.Update[ S ]]
   type Groups[       S <: Sys[ S ]]   = LinkedList.Modifiable[ S, Group[ S ], GroupUpdate[ S ]]
   type GroupsUpdate[ S <: Sys[ S ]]   = LinkedList.Update[     S, Group[ S ], GroupUpdate[ S ]]

   def read(  dir: File ) : Document[ Cf ] = Impl.read( dir )
   def empty( dir: File ) : Document[ Cf ] = Impl.empty( dir )
}
trait Document[ S <: Sys[ S ]] {
   import Document._

   def system: S
   def cursor: Cursor[ S ]
   def folder: File
   def groups( implicit tx: S#Tx ) : Groups[ S ]
}