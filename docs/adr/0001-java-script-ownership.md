# Java scripts with a DreamBot source API

The maintained catalog and diagnostic console use one Java execution model,
replacing the interpreter at the user's request. Scripts compile against an
independent implementation of supported DreamBot public APIs; this retains
familiar source interfaces while allowing the client to own snapshots, input,
and cancellation directly. There is no interpreter fallback, source translator,
or duplicate catalog, and private DreamBot internals are outside the contract.

Java runs in the client JVM as trusted code. A per-run classloader and revocable
SDK input authority provide lifetime ownership, but do not sandbox arbitrary
Java side effects or forcibly terminate uncooperative user code.
