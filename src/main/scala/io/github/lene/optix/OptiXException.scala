package io.github.lene.optix

/** Base type for failures surfaced from the optix-jni native seam (Sprint 35 F11/CR-9).
  *
  * Native JNI code routes runtime failures through this type — `throwException(env,
  * "io/github/lene/optix/OptiXException", ...)` — instead of a bare `java.lang.RuntimeException`,
  * so the application has a single optix-jni-owned type to translate at its boundary. It extends
  * `RuntimeException`, so any existing broad `catch` keeps working while callers gain a precise
  * type to match on.
  *
  * Argument-precondition violations are deliberately NOT part of this hierarchy: they stay as
  * `IllegalArgumentException` (Scala `require`, and the native arg-validation throws for array
  * length / texture dimensions / light count). That split is asserted by `JniErrorSurfaceSuite`.
  */
class OptiXException(message: String, cause: Throwable)
    extends RuntimeException(message, cause):

  /** The constructor JNI actually binds to.
    *
    * `throwException` (JNIBindings.cpp) calls `env->ThrowNew(cls, msg)`, and `ThrowNew` looks up
    * `<init>(Ljava/lang/String;)V` by exact signature. A Scala default parameter does NOT emit a
    * single-argument overload — `(message: String, cause: Throwable = null)` compiles to one
    * `<init>(String, Throwable)` plus a `$lessinit$greater$default$2` getter applied at the call
    * site. So every native failure died with `NoSuchMethodError: OptiXException: method
    * 'void <init>(java.lang.String)' not found`, masking the real error instead of surfacing it.
    * Declaring the overload explicitly is what makes the native error path work at all.
    */
  def this(message: String) = this(message, null) // scalafix:ok DisableSyntax.null
