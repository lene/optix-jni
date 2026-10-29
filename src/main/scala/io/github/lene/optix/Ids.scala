package io.github.lene.optix

// menger#110: instance ids, mesh slots and texture indices were all raw Ints, so one could be
// passed where another was meant and a -1 failure code passed on as an index. Opaque types make
// them distinct in Scala and erase to `int` in bytecode: Java and Kotlin callers, and the JNI
// signatures, see plain ints.

/** An IAS instance, as an `add*Instance` call returns it. */
opaque type InstanceId = Int

object InstanceId:
  /** An instance id from its number (`>= 0`), e.g. read back from a log or a test. */
  def apply(value: Int): InstanceId =
    require(value >= 0, s"an instance id is >= 0, got $value")
    value
  extension (id: InstanceId) def value: Int = id
  extension (ids: Array[InstanceId]) private[optix] def values: Array[Int] = ids

/** A triangle mesh uploaded to the renderer, as `addTriangleMesh`/`setProjectedMesh` return it. */
opaque type MeshSlot = Int

object MeshSlot:
  /** A mesh slot from its number (`>= 0`). */
  def apply(value: Int): MeshSlot =
    require(value >= 0, s"a mesh slot is >= 0, got $value")
    value
  extension (slot: MeshSlot) def value: Int = slot

/** An uploaded texture, as `uploadTexture` returns it, or [[TextureIndex.NoTexture]]. */
opaque type TextureIndex = Int

object TextureIndex:
  /** No texture: the native renderer's "none" (-1), the default of every texture parameter. */
  val NoTexture: TextureIndex = -1
  /** A texture index from its number (`>= 0`). */
  def apply(value: Int): TextureIndex =
    require(value >= 0, s"a texture index is >= 0, got $value")
    value
  extension (index: TextureIndex) def value: Int = index
