package scalaprops

import scalaz._
import scalaz.std.tuple._
import scalaz.std.anyVal._

object KleisliTest extends Scalaprops {

  private[this] val e = new FunctionEqual(3)

  implicit def kleisliEqual[F[_], A: Gen, B](implicit E: Equal[F[B]]): Equal[Kleisli[F, A, B]] = {
    import e._
    Equal[A => F[B]].contramap(_.run)
  }

  private[this] implicit def kleisliMonadState[F[_, _], A1](implicit F: MonadState[F, A1]): MonadState[({type x[a, b] = Kleisli[({type y[c] = F[A1, c]})#y, a, b]})#x, A1] =
    new MonadState[({type x[a, b] = Kleisli[({type y[c] = F[A1, c]})#y, a, b]})#x, A1] {
      type G[A] = F[A1, A]

      override val init =
        Kleisli.kleisliMonadTrans[A1].liftMU(F.init)

      override val get =
        Kleisli.kleisliMonadTrans[A1].liftMU(F.get)

      override def put(s: A1) =
        Kleisli.kleisliMonadTrans[A1].liftMU(F.put(s))

      override def point[A](a: => A) =
        Kleisli.kleisliMonadReader[G, A1].point(a)

      override def bind[A, B](fa: Kleisli[G, A1, A])(f: A => Kleisli[G, A1, B]) =
        fa flatMap f
    }

  private[this] final class KleisliMonadStateTestHelper[F[_], S0]{
    type S = S0
    type F1[A] = F[A]
    type F2[A, B] = StateT[F1, A, B]
    type F3[A] = F2[S, A]
    type F4[A, B] = Kleisli[F3, A, B]
  }

  val monadStateId = {
    import StateTTest.stateTEqual
    import FunctionEqual._
    val H = new KleisliMonadStateTestHelper[Id.Id, Byte]
    import H._
    implicit val k = Gen.kleisli[F3, S, Unit]
    scalazlaws.monadState.laws[F4, S]
  }

  val monadStateIList = {
    import StateTTest.stateTEqual
    import FunctionEqual._
    val H = new KleisliMonadStateTestHelper[IList, Byte]
    import H._
    implicit val k = Gen.kleisli[F3, S, Unit]
    scalazlaws.monadState.laws[F4, S]
  }

  val monadStateMaybe = {
    import StateTTest.stateTEqual
    import FunctionEqual._
    val H = new KleisliMonadStateTestHelper[Maybe, Byte]
    import H._
    implicit val k = Gen.kleisli[F3, S, Unit]
    scalazlaws.monadState.laws[F4, S]
  }

  val monadStateTree = {
    import StateTTest.stateTEqual
    import FunctionEqual._
    val H = new KleisliMonadStateTestHelper[Tree, Byte]
    import H._
    scalazlaws.monadState.laws[F4, S]
  }


  private def kleisliTest[F[_]: MonadPlus: Zip](implicit
    F: Equal[F[Int]],
    E1: Equal[F[(Int, Int)]],
    E2: Equal[F[(Int, (Int, Int))]],
    E3: Equal[F[((Int, Int), Int)]],
    G1: Gen[Kleisli[F, Int, Int]],
    G2: Gen[Kleisli[F, Int, Int => Int]]
  ) = {
    type K1[a] = Kleisli[F, Int, a]
    type K2[a, b] = Kleisli[F, a, b]

    Properties.list(
      scalazlaws.monadPlus.all[K1],
      scalazlaws.zip.all[K1],
      scalazlaws.arrow.all[K2]
    )
  }

  private val sizeSetting = Foldable1[NonEmptyList].foldLeft1(
    NonEmptyList(
      ScalazLaw.composeAssociative,
      ScalazLaw.plusAssociative,
      ScalazLaw.semigroupAssociative
    ).map { law =>
      { case `law` => Param.maxSize(30) }: PartialFunction[ScalazLaw, Endo[Param]]
    }
  )(_ orElse _)


  val testMaybe = kleisliTest[Maybe]

  val disjunctionMonadError = {
    implicit def gen0[F[_, _], A, B, C](implicit G: Gen[A => F[B, C]], B: Bind[({type l[a] = F[B, a]})#l]): Gen[Kleisli[({type l[a] = F[B, a]})#l, A, C]] =
      G.map(Kleisli.kleisliU(_))

    implicit def equal0[F[_, _], A, B, C](implicit E: Equal[A => F[B, C]]): Equal[Kleisli[({type l[a] = F[B, a]})#l, A, C]] =
      E.contramap(_.run)

    import e._

    scalazlaws.monadError.laws[({type x[a, b] = Kleisli[({type y[c] = a \/ c})#y, Byte, b]})#x, Byte]
  }

  val testIList = kleisliTest[IList].andThenParamPF{
    case Or.R(Or.L(p)) if sizeSetting.isDefinedAt(p) => sizeSetting(p)
  }

  val testNonEmptyList = {
    type K1[a] = Kleisli[NonEmptyList, Byte, a]
    type K2[a, b] = Kleisli[NonEmptyList, a, b]

    Properties.list(
      scalazlaws.monad.all[K1],
      scalazlaws.plus.all[K1],
      scalazlaws.zip.all[K1],
      scalazlaws.arrow.all[K2]
    )
  }.andThenParamPF{
    case Or.R(Or.L(p)) if sizeSetting.isDefinedAt(p) => sizeSetting(p)
  }

  val monadTrans = scalazlaws.monadTrans.all[({type l[f[_], a] = Kleisli[f, Int, a]})#l]

}
