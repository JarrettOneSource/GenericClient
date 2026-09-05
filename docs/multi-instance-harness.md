# Multi-instance harness

GenericClient runs one RuneLite account per JVM. The external harness owns
process lifecycle, instance discovery, account selection, and command routing.
This keeps injected-client state, script workers, input, and failures isolated
between accounts.

Supervised clients use an assigned loopback port and publish atomic instance
descriptors. The harness verifies PID and endpoint identity before issuing a
command. Its dashboard routes mutations to one selected instance, caches
screenshots per instance, and streams changes in fleet state.

Jagex Launcher handoffs remain the account/session bootstrap. The normal Play
flow starts full RuneLite; dense displayless mode is an explicit harness option.
Both use the same Java script catalog and control protocol. Behavior and schedule
state are keyed by the derived account profile. Catalog installation and source
compilation require one writer for the shared directory.

See [Linux harness operation](linux-harness-poc.md) and
[dashboard contracts](harness-dashboard.md) for the implemented commands,
configuration, and live-proof boundaries. The harness test suite covers instance
selection, launch handoffs, process validation, command restrictions, screenshots,
and state updates.
