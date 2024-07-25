package scalaprops

import scalaprops.internal._

final case class Property(f: (Int, Rand) => (Rand, Result)) {
  def toCheck: Check =
    Check(this)

  def toCheckWith(endo: Endo[Param]): Check =
    Check(this, endo)

  def resize(size: Int): Property =
    Property.fromGen(gen.resize(size))

  def mapSize(g: Int => Int): Property =
    Property.fromGen(gen.mapSize(g))

  def gen: Gen[Result] = Gen.gen(f)

  def and(p: Property): Property =
    Property.fromGen(
      Gen.from2((res1: Result, res2: Result) =>
        if res1.isException || res1.isFalsified then {
          res1
        } else if res2.isException || res2.isFalsified then {
          res2
        } else if res1.isProven || res1.isUnfalsified then {
          res2
        } else if res2.isProven || res2.isUnfalsified then {
          res1
        } else Result.NoResult
      )(gen, p.gen)
    )

  def or(p: Property): Property =
    Property.fromGen(
      Gen.from2((res1: Result, res2: Result) =>
        if res1.isException || res1.isFalsified then {
          res1
        } else if res2.isException || res2.isFalsified then {
          res2
        } else if res1.isProven || res1.isUnfalsified then {
          res1
        } else if res2.isProven || res2.isUnfalsified then {
          res2
        } else Result.NoResult
      )(gen, p.gen)
    )

  def sequence(p: Property): Property =
    Property.fromGen(
      Gen.from2((res1: Result, res2: Result) =>
        if res1.isException || res1.isProven || res1.isUnfalsified then {
          res1
        } else if res2.isException || res2.isProven || res2.isUnfalsified then {
          res2
        } else if res1.isFalsified then {
          res2
        } else if res2.isFalsified then {
          res1
        } else Result.NoResult
      )(gen, p.gen)
    )

  // TODO remove `listener` parameter?
  def check(param: Param, cancel: () => Boolean, listener: Int => Unit): CheckResult = {
    import param._
    @annotation.tailrec
    def loop(s: Int, discarded: Int, sz: Float, random: Rand): CheckResult =
      if cancel() then {
        CheckResult.Timeout(s, discarded, seed)
      } else {
        val size: Float = {
          if s == 0 && discarded == 0 then minSize.toFloat
          else sz + (maxSize - sz) / (minSuccessful - s)
        }

        val r =
          try {
            Right(f(math.round(size), random))
          } catch {
            case e: Throwable =>
              Left(e)
          }

        r match {
          case Right((nextRand, Result.NoResult)) =>
            if discarded + 1 >= maxDiscarded then {
              CheckResult.Exhausted(s, discarded + 1, seed)
            } else {
              loop(s, discarded + 1, size, nextRand)
            }
          case Right((_, Result.Proven)) =>
            CheckResult.Proven(s + 1, discarded, seed)
          case Right((nextRand, Result.Unfalsified(_))) =>
            if s + 1 >= minSuccessful then {
              CheckResult.Passed(s + 1, discarded, seed)
            } else {
              listener(s)
              loop(s + 1, discarded, size, nextRand)
            }
          case Right((_, Result.Falsified(args))) =>
            CheckResult.Falsified(s, discarded, args, seed)
          case Right((_, Result.Exception(args, ex))) =>
            CheckResult.PropException(s, discarded, args, ex, seed)
          case Right((_, Result.Ignored(reason))) =>
            CheckResult.Ignored(s, discarded, reason, seed)
          case Left(e) =>
            CheckResult.GenException(s, discarded, e, seed)
        }
      }

    loop(0, 0, minSize, param.rand)
  }

  def toProperties[A](id: A, param: Endo[Param] = Param.id): Properties[A] =
    Properties.single(id, Check(this, param))

  def ignore(reason: String): Property =
    Property((_, rand) => (rand, Result.Ignored(reason)))
}

object Property {
  private[this] val noResult = propFromResult(Result.NoResult)

  def implies(b: => Boolean, p: => Property): Property =
    if b then {
      p
    } else {
      noResult
    }

  def fromGen(g: Gen[Result]): Property =
    Property(g.f)

  def propFromResultLazy(r: Lazy[Result]): Property =
    Property((_, rand) => (rand, r.value))

  def propFromResult(r: Result): Property =
    Property((_, rand) => (rand, r))

  val prop: Boolean => Property = b =>
    propFromResult {
      if b then Result.Proven
      else Result.Falsified(List.empty)
    }

  private[this] def propLazy(result: Lazy[Boolean]): Property =
    propFromResultLazy {
      result.map { r =>
        if r then Result.Proven
        else Result.Falsified(List.empty)
      }
    }

  def forall0[A](g: Gen[A], shrink: Shrink[A])(f: A => Property): Property =
    Property((i, r) => {
      def first(as: Stream[(Rand, A)], shrinks: Int): Option[(A, Result, Rand)] = {
        as.map { case (rr, a) =>
          val x = exception(f(a)).f(i, rr)
          x._2.toOption.map(result => (a, result.provenAsUnfalsified.addArg(Arg(a, shrinks)): Result, x._1))
        } match {
          case Stream() =>
            Option.empty
          case results @ (h #:: _) =>
            results.find(_.exists(_._2.failed)).getOrElse(h)
        }
      }

      first(Stream(g.f(i, r)), 0) match {
        case Some(xx @ (_, re, rand)) if re.failed =>
          @annotation.tailrec
          def loop(shrinks: Int, x: (A, Result, Rand)): (Rand, Result) =
            first(shrink(x._1).map(rand.next -> _), shrinks) match {
              case Some((aa, result @ Result.Falsified(args), rr)) if args.count(_.value == aa) <= 1 =>
                loop(shrinks + 1, (aa, result, rr.next))
              case _ =>
                (x._3, x._2)
            }
          loop(1, xx)
        case xx =>
          xx.map(t => (t._3, t._2)).getOrElse((Rand.fromSeed(), Result.NoResult))
      }
    })

  def exception(p: => Property): Property =
    try {
      p
    } catch {
      case t: Throwable =>
        Property((i, r) => (r, Result.Exception(List.empty, t)))
    }

  def forAll(result: => Boolean): Property =
    propLazy(Lazy(result))

  def forAll[A1](f: A1 => Boolean)(implicit A1: Gen[A1]): Property =
    forAllS(f)(A1, Shrink.empty)

  def forAll[A1, A2](f: (A1, A2) => Boolean)(implicit A1: Gen[A1], A2: Gen[A2]): Property =
    forAllS(f)(A1, A2, Shrink.empty, Shrink.empty)

  def forAll[A1, A2, A3](f: (A1, A2, A3) => Boolean)(implicit A1: Gen[A1], A2: Gen[A2], A3: Gen[A3]): Property =
    forAllS(f)(A1, A2, A3, Shrink.empty, Shrink.empty, Shrink.empty)

  def forAll[A1, A2, A3, A4](
    f: (A1, A2, A3, A4) => Boolean
  )(implicit A1: Gen[A1], A2: Gen[A2], A3: Gen[A3], A4: Gen[A4]): Property =
    forAllS(f)(A1, A2, A3, A4, Shrink.empty, Shrink.empty, Shrink.empty, Shrink.empty)

  def forAll[A1, A2, A3, A4, A5](
    f: (A1, A2, A3, A4, A5) => Boolean
  )(implicit A1: Gen[A1], A2: Gen[A2], A3: Gen[A3], A4: Gen[A4], A5: Gen[A5]): Property =
    forAllS(f)(A1, A2, A3, A4, A5, Shrink.empty, Shrink.empty, Shrink.empty, Shrink.empty, Shrink.empty)

  /** `forAll` with explicit `Gen` */
  def forAllG[A1](A1: Gen[A1])(f: A1 => Boolean): Property =
    forAll[A1](f)(A1)

  /** `forAll` with explicit `Gen` */
  def forAllG[A1, A2](A1: Gen[A1], A2: Gen[A2])(f: (A1, A2) => Boolean): Property =
    forAll[A1, A2](f)(A1, A2)

  /** `forAll` with explicit `Gen` */
  def forAllG[A1, A2, A3](A1: Gen[A1], A2: Gen[A2], A3: Gen[A3])(f: (A1, A2, A3) => Boolean): Property =
    forAll[A1, A2, A3](f)(A1, A2, A3)

  /** `forAll` with explicit `Gen` */
  def forAllG[A1, A2, A3, A4](A1: Gen[A1], A2: Gen[A2], A3: Gen[A3], A4: Gen[A4])(
    f: (A1, A2, A3, A4) => Boolean
  ): Property =
    forAll[A1, A2, A3, A4](f)(A1, A2, A3, A4)

  /** `forAll` with explicit `Gen` */
  def forAllG[A1, A2, A3, A4, A5](A1: Gen[A1], A2: Gen[A2], A3: Gen[A3], A4: Gen[A4], A5: Gen[A5])(
    f: (A1, A2, A3, A4, A5) => Boolean
  ): Property =
    forAll[A1, A2, A3, A4, A5](f)(A1, A2, A3, A4, A5)

  def property1[A1](f: A1 => Property)(implicit A1: Gen[A1], S1: Shrink[A1]): Property =
    forall0(A1, S1)(f)

  def property2[A1, A2](
    f: (A1, A2) => Property
  )(implicit A1: Gen[A1], A2: Gen[A2], S1: Shrink[A1], S2: Shrink[A2]): Property =
    forall0(A1, S1)(a1 => forall0(A2, S2)(a2 => f(a1, a2)))

  def property3[A1, A2, A3](
    f: (A1, A2, A3) => Property
  )(implicit A1: Gen[A1], A2: Gen[A2], A3: Gen[A3], S1: Shrink[A1], S2: Shrink[A2], S3: Shrink[A3]): Property =
    forall0(A1, S1)(a1 => forall0(A2, S2)(a2 => forall0(A3, S3)(a3 => f(a1, a2, a3))))

  def property[A1](f: A1 => Property)(implicit A1: Gen[A1], S1: Shrink[A1]): Property =
    property1(f)

  def property[A1, A2](
    f: (A1, A2) => Property
  )(implicit A1: Gen[A1], A2: Gen[A2], S1: Shrink[A1], S2: Shrink[A2]): Property =
    property2(f)

  def property[A1, A2, A3](
    f: (A1, A2, A3) => Property
  )(implicit A1: Gen[A1], A2: Gen[A2], A3: Gen[A3], S1: Shrink[A1], S2: Shrink[A2], S3: Shrink[A3]): Property =
    property3(f)

  object NoShrink {
    def property1[A1](f: A1 => Property)(implicit A1: Gen[A1], S1: Shrink[A1] = Shrink.empty[A1]): Property =
      Property.property1(f)

    def property2[A1, A2](f: (A1, A2) => Property)(implicit
      A1: Gen[A1],
      A2: Gen[A2],
      S1: Shrink[A1] = Shrink.empty[A1],
      S2: Shrink[A2] = Shrink.empty[A2]
    ): Property =
      Property.property2(f)

    def property3[A1, A2, A3](f: (A1, A2, A3) => Property)(implicit
      A1: Gen[A1],
      A2: Gen[A2],
      A3: Gen[A3],
      S1: Shrink[A1] = Shrink.empty[A1],
      S2: Shrink[A2] = Shrink.empty[A2],
      S3: Shrink[A3] = Shrink.empty[A3]
    ): Property =
      Property.property3(f)
  }

  def forAllS[A1](f: A1 => Boolean)(implicit A1: Gen[A1], S1: Shrink[A1]): Property =
    forall0(A1, S1)(f.andThen(prop))

  def forAllS[A1, A2](
    f: (A1, A2) => Boolean
  )(implicit A1: Gen[A1], A2: Gen[A2], S1: Shrink[A1], S2: Shrink[A2]): Property =
    forall0(A1, S1)(a1 => forall0(A2, S2)(a2 => prop(f(a1, a2))))

  def forAllS[A1, A2, A3](
    f: (A1, A2, A3) => Boolean
  )(implicit A1: Gen[A1], A2: Gen[A2], A3: Gen[A3], S1: Shrink[A1], S2: Shrink[A2], S3: Shrink[A3]): Property =
    forall0(A1, S1)(a1 => forall0(A2, S2)(a2 => forall0(A3, S3)(a3 => prop(f(a1, a2, a3)))))

  def forAllS[A1, A2, A3, A4](f: (A1, A2, A3, A4) => Boolean)(implicit
    A1: Gen[A1],
    A2: Gen[A2],
    A3: Gen[A3],
    A4: Gen[A4],
    S1: Shrink[A1],
    S2: Shrink[A2],
    S3: Shrink[A3],
    S4: Shrink[A4]
  ): Property =
    forall0(A1, S1)(a1 => forall0(A2, S2)(a2 => forall0(A3, S3)(a3 => forall0(A4, S4)(a4 => prop(f(a1, a2, a3, a4))))))

  def forAllS[A1, A2, A3, A4, A5](f: (A1, A2, A3, A4, A5) => Boolean)(implicit
    A1: Gen[A1],
    A2: Gen[A2],
    A3: Gen[A3],
    A4: Gen[A4],
    A5: Gen[A5],
    S1: Shrink[A1],
    S2: Shrink[A2],
    S3: Shrink[A3],
    S4: Shrink[A4],
    S5: Shrink[A5]
  ): Property =
    forall0(A1, S1)(a1 =>
      forall0(A2, S2)(a2 =>
        forall0(A3, S3)(a3 => forall0(A4, S4)(a4 => forall0(A5, S5)(a5 => prop(f(a1, a2, a3, a4, a5)))))
      )
    )
}
