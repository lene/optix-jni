package io.github.lene.optix

/** A sticky CUDA error (illegal address, launch failure, ...) made this process's CUDA context
  * unusable (optix-jni#73). The first one is latched natively; from then on every render
  * throws this, naming it, instead of launching again into the broken context. Only a new
  * process recovers: catch this to save state and exit, not to retry.
  */
class OptiXContextLostException(message: String, cause: Throwable)
    extends OptiXException(message, cause):

  /** The constructor JNI's `ThrowNew` binds to (see [[OptiXException]]). */
  def this(message: String) = this(message, null) // scalafix:ok DisableSyntax.null
