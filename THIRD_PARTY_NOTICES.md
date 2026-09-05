# Third-party notices

## Skretzo/shortest-path collision map

GenericClient includes `collision-map.zip` from
[`Skretzo/shortest-path`](https://github.com/Skretzo/shortest-path) commit
`44a691aafad48bd8f4ef6d00680d627d2aa8153c`.

- Upstream artifact:
  `src/main/resources/collision-map.zip`
- Vendored SHA-256:
  `2fca3c83778995c96a6511cc523e157352ef526f3b0a969892b62010d5c5e717`
- Upstream license: BSD 2-Clause

The upstream repository's license text is reproduced in
`src/main/resources/com/genericclient/navigation/shortest-path-LICENSE.txt`.
The upstream file at the pinned commit contains the literal placeholders
`<YEAR>` and `<COPYRIGHT HOLDER>`; they are preserved exactly instead of being
silently attributed by GenericClient.

`door-map.zip` is generated from OpenRS2 cache 2686 with RuneLite cache tools
and the `osrs-pathfinding/shortest-path-tooling` collision dumper. Its vendored
SHA-256 is
`a5d95b4ddecda08bf0016af72f48b358b68d34d0af7930c3ae55eb57cd3eb2ec`.

## Infinitay/Random-Event-Helper

The Capt' Arnav solver's dial model and RuneLite interface mapping were
cross-checked against
[`Infinitay/Random-Event-Helper`](https://github.com/Infinitay/Random-Event-Helper)
commit `43e578fd30f60ac765a32b7b99c82b6ca3791776`.

- Upstream license: BSD 2-Clause
- License copy:
  `src/main/resources/com/genericclient/random-events/Random-Event-Helper-LICENSE.txt`

GenericClient does not load, install, or depend on the reference plugin.
