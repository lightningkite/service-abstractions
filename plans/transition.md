## Transitioning a module from Lightning Server to Service Abstractions

- Move from a `SomethingSettings` serializable object that implements `()->T` to a value class with a single value, `url`, that implements `Settings<T>` instead.  This will give us access to a `SettingContext` in construction.  The new settings class should be inside the parent interface.
- Every implementation should track metrics, and should do so via a base implementation of the interface.
- Never have anything static except for the pluggable settings.
- Use explicit visibility modifiers.
