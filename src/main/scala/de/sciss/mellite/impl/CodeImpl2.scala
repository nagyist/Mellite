/*
 *  CodeImpl2.scala
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

package de.sciss
package mellite
package impl

import scala.concurrent._
import java.io.File
import de.sciss.processor.Processor
import scala.tools.nsc
import scala.tools.nsc.interpreter.{Results, IMain}
import scala.tools.nsc.{ConsoleWriter, NewLinePrintWriter}
import de.sciss.processor.impl.ProcessorImpl
import collection.immutable.{Seq => ISeq}
import reflect.runtime.universe.{typeTag, TypeTag}
import scala.util.{Success, Failure}
import scala.concurrent.duration.Duration

object CodeImpl2 {

  // ---- internals ----

  object Wrapper {
    implicit object FileTransform
      extends Wrapper[(File, File, Processor[Any, _] => Unit), Future[Unit], Code.FileTransform] {

      def imports: ISeq[String] = ISeq(
        "de.sciss.synth.io.{AudioFile, AudioFileSpec, AudioFileType, SampleFormat, Frames}",
        "de.sciss.synth._",
        "de.sciss.file._",
        "de.sciss.fscape.{FScapeJobs => fscape}",
        "de.sciss.mellite.TransformUtil._"
      )

      def binding: Option[String] = Some("FileTransformContext")

      def wrap(args: (File, File, Processor[Any, _] => Unit))(fun: => Any): Future[Unit] = {
        val (in, out, procHandler) = args
        val proc = new FileTransformContext(in, out, () => fun)
        procHandler(proc)
        proc.start()
        proc
      }

      def blockTag = typeTag[Unit]
    }

    implicit object SynthGraph
      extends Wrapper[Unit, synth.SynthGraph, Code.SynthGraph] {

      def imports: ISeq[String] = ISeq(
        "de.sciss.synth._",
        "de.sciss.synth.ugen._",
        "de.sciss.synth.proc.graph._"
      )

      def binding = None

      def wrap(in: Unit)(fun: => Any): synth.SynthGraph = synth.SynthGraph(fun)

      def blockTag = typeTag[Unit]
    }
  }
  trait Wrapper[In, Out, Repr] {
    def imports: ISeq[String]
    def binding: Option[String]

    /** When `execute` is called, the result of executing the compiled code
      * is passed into this function.
      *
      * @param in   the code type's input
      * @param fun  the thunk that executes the coe
      * @return     the result of `fun` wrapped into type `Out`
      */
    def wrap(in: In)(fun: => Any): Out

    /** TypeTag of */
    def blockTag: TypeTag[_]
  }

  def execute[I, O, Repr <: Code { type In = I; type Out = O }](code: Repr, in: I)
                      (implicit w: Wrapper[I, O, Repr]): O = {
    w.wrap(in) {
      compileThunk(code.source, w, execute = true)
    }
  }

  def compileBody[I, O, Repr <: Code { type In = I; type Out = O }](code: Repr)
                      (implicit w: Wrapper[I, O, Repr]): Future[Unit] =
    Future {
      blocking {
        compileThunk(code.source, w, execute = false)
      }
    }

  private final class Intp(cSet: nsc.Settings)
    extends IMain(cSet, new NewLinePrintWriter(new ConsoleWriter, autoFlush = true)) {

    override protected def parentClassLoader = CodeImpl2.getClass.getClassLoader
  }

  private lazy val intp = {
    val cSet = new nsc.Settings()
    cSet.classpath.value += File.pathSeparator + sys.props("java.class.path")
    val res = new Intp(cSet)
    res.initializeSynchronous()
    res
  }

  object Run {
    def apply[A](execute: Boolean)(thunk: => A): A = if (execute) thunk else null.asInstanceOf[A]
  }

  sealed trait Context[A] {
    def __context__(): A
  }
  
  object FileTransformContext extends Context[FileTransformContext#Bindings] {
    private[CodeImpl2] val contextVar = new ThreadLocal[FileTransformContext#Bindings]
    def __context__(): FileTransformContext#Bindings = contextVar.get()
  }

  final class FileTransformContext(in: File, out: File, fun: () => Any)
    extends ProcessorImpl[Unit, FileTransformContext] {
    process =>

    type Bindings = Bindings.type
    object Bindings {
      def in : File = process.in
      def out: File = process.out
      def checkAborted(): Unit = process.checkAborted()
      def progress(f: Double): Unit = {
        process.progress(f.toFloat)
        process.checkAborted()
      }
    }

    protected def body(): Unit = {
      // blocking {
      val prom  = Promise[Unit]()
      val t = new Thread {
        override def run(): Unit = {
          FileTransformContext.contextVar.set(Bindings)
          try {
            fun()
            prom.complete(Success {})
          } catch {
            case e: Exception =>
              e.printStackTrace()
              prom.complete(Failure(e))
          }
        }
      }
      t.start()
      Await.result(prom.future, Duration.Inf)
      // }
    }
  }

  private val pkg = "de.sciss.mellite.impl.CodeImpl2"

  // note: synchronous
  private def compileThunk(code: String, w: Wrapper[_, _, _], execute: Boolean): Any = {
    val i = intp

    val impS  = w.imports.map(i => s"  import $i\n").mkString
    val bindS = w.binding.map(i =>
     s"""  val __context__ = $pkg.$i.__context__
        |  import __context__._
      """.stripMargin
    ).getOrElse("")
    val aTpe  = w.blockTag.tpe.toString
    val synth =
     s"""$pkg.Run[$aTpe]($execute) {
        |$impS
        |$bindS
        |
        |""".stripMargin + code + "\n}"

    val res = i.interpret(synth)
    // commented out to chase ClassNotFoundException
    // i.reset()
    res match {
      case Results.Success =>
        if (aTpe == "Unit" || !execute) () else {
          val n = i.mostRecentVar
          i.valueOfTerm(n).getOrElse(sys.error(s"No value for term $n"))
        }

      case Results.Error      => throw Code.CompilationFailed()
      case Results.Incomplete => throw Code.CodeIncomplete()
    }
  }
}