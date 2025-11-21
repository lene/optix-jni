# OptiX JNI Public Release Checklist

This checklist tracks items to address before releasing optix-jni as a standalone library.

## API Exposure

### Cache Management
- [ ] Expose `OptiXContext::getDefaultCachePath()` via JNI
- [ ] Expose `OptiXContext::clearCache()` via JNI
- [ ] Expose `OptiXContext::clearCache(path)` via JNI
- [ ] Consider: dedicated `OptiXCache` Scala object vs static methods on `OptiXRenderer`

**Why:** Users may need programmatic cache management for:
- CI/CD pipelines that need deterministic builds
- Automated recovery from corruption
- Custom cache locations in multi-tenant environments

**Current state:** Auto-recovery works internally; manual clearing via shell command.

### Ray Statistics
- [ ] Review `RayStats` API for completeness
- [ ] Consider exposing individual stat accessors if needed

### Error Handling
- [ ] Review exception types - are they specific enough for library users?
- [ ] Document all exceptions that can be thrown

## Documentation

- [ ] API documentation (Scaladoc for all public methods)
- [ ] Usage examples in README
- [ ] Migration guide if API changes from current menger usage
- [ ] Minimum system requirements (CUDA version, OptiX version, GPU compute capability)

## Packaging

- [ ] Separate sbt project or publish from submodule?
- [ ] Native library bundling strategy (fat JAR vs separate native artifact)
- [ ] Platform support matrix (Linux x86_64, others?)
- [ ] Version numbering scheme

## Testing

- [ ] Ensure all public API methods have tests
- [ ] Integration tests that don't require menger core
- [ ] Document test requirements (GPU, CUDA, OptiX SDK)

## Licensing

- [ ] License file in optix-jni directory
- [ ] License headers in source files
- [ ] Third-party license compliance (OptiX SDK license terms)

## CI/CD

- [ ] Standalone CI pipeline for optix-jni
- [ ] Publish to Maven Central or other repository
- [ ] Automated release process

---

*Last updated: 2025-11-21*
*Add items as they come up during development.*
