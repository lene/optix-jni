# Constant ambient references (optix-jni#106)

`sponge.png`, `sponge-backlit.png` and `glass-gold-cylinder.png` (320x240) are renders of
`ConstantAmbientScenes` by optix-jni **b13c283**, the commit before the occlusion ambient existed.
`AmbientOcclusionSuite` asserts that `AmbientModel.Constant` still renders them byte for byte.

GPU: NVIDIA RTX A1000 6GB Laptop GPU

They are not tolerance-compared references: a change here means the constant path drifted. Byte
equality holds on the GPU they were rendered on, named in the `GPU:` line above
(`nvidia-smi --query-gpu=name --format=csv,noheader`); `AmbientOcclusionSuite` reads that line and
cancels the test, naming both GPUs, on any other GPU, where the last bit may differ.

Regenerate (only if a scene changes on purpose): check out b13c283 in a throwaway worktree, copy
`src/test/scala/io/github/lene/optix/ConstantAmbientScenes.scala` into it, render each scene of
`ConstantAmbientScenes.all` (renderer from `RendererFixture`, `clearAllInstances()` before each
`build(renderer)`, `renderWithStats(imageSize)`) and save with `TestUtilities.savePNG`.
