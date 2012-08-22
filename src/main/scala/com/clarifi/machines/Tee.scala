package com.clarifi.machines

import scalaz._

import Machine._

sealed trait T[-I, -J] extends Covariant {
  def lmap[B](h: B => I): T[B, J]
  def rmap[B](h: B => J): T[I, B]

  def fold[R](kl: (I => X) => R, kr: (J => X) => R): R
}

case class L[-I, O](f: I => O) extends T[I, Any] {
  type X = O

  def lmap[B](h: B => I)   = L(f compose h)
  def rmap[B](H: B => Any) = this

  def fold[R](kl: (I => X) => R, kr: (Any => X) => R) = kl(f)
}

case class R[-J, O](f: J => O) extends T[Any, J] {
  type X = O

  def lmap[B](h: B => Any) = this
  def rmap[B](h: B => J)   = R(f compose h)

  def fold[R](kl: (Any => X) => R, kr: (J => X) => R) = kr(f)
}

object Tee {
  import ProcessCategory._
  import Plan._

  import scalaz.syntax.order._

//  /* def mergeOuter[A, B, K:Order]: Tee[(K, List[A]), (K, List[B]), These[A, B]] =
//    awaits(left[(K, List[A])]) flatMap {
//      case (k, as) => sys.error("sr")
//    } orElse flattened(right[List[B]]).inmap(_._2) */

  def tee[A, AA, B, BB, C](ma: Process[A, AA], mb: Process[B, BB], m: Tee[AA, BB, C]): Tee[A, B, C] = {
    m match {
      case Stop => Stop
      case Emit(o, k) => Emit(o, () => tee(ma, mb, k()))
      case Await(k, s, f) =>
        s fold (
          kl => ma match {
            case Stop => tee(stopped, mb, f())
            case Emit(a, next) => tee(next(), mb, k(kl(a)))
            case Await(g, kg, fg) =>
              Await(
                (maa: Process[A, AA]) => tee(maa, mb, m),
                L(a => g(kg(a))),
                () => tee(fg(), mb, m)
              )
          },
          kr => mb match {
            case Stop => tee(ma, stopped, f())
            case Emit(b, next) => tee(ma, next(), k(kr(b)))
            case Await(g, kg, fg) =>
              Await(
                (mbb: Process[B, BB]) => tee(ma, mbb, m),
                R((b: B) => g(kg(b))),
                () => tee(ma, fg(), m)
              )
          }
        )
    }
  }

  def addL[A, B, C, D](p: Process[A, B], t: Tee[B, C, D]): Tee[A, C, D] =
    tee(p, id, t)

  def addR[A, B, C, D](p: Process[B, C], t: Tee[A, C, D]): Tee[A, B, D] =
    tee(id, p, t)

  def capL[A, B, C](s: Source[A], t: Tee[A, B, C]): Process[B, C] =
    addL(s, t) inmap cappedT

  def capR[A, B, C](s: Source[B], t: Tee[A, B, C]): Process[A, C] =
    addR(s, t) inmap cappedT

  def cappedT[A](t: T[A, A]): S[A] = t.fold(Fun(_), Fun(_))

  def left[A]: Handle[T[A, Any], A] =
    new Handle[T[A, Any], A] {
      def apply[R](f: A => R) = L(f)
    }

  def right[A]: Handle[T[Any, A], A] =
    new Handle[T[Any, A], A] {
      def apply[R](f: A => R) = R(f)
    }
}
