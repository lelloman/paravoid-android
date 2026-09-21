# Same-package resource split proof

First implementation slice of [the packaging contract](../../PACKAGING.md).
This is a bounded fixture, not automatic resource splitting for downstream apps.

## Linker proof

```sh
python3 compatibility/resource-split/pack-resources.py "$ANDROID_HOME" /tmp/paravoid-resource-split-link
python3 -m unittest discover -s compatibility/resource-split -p 'test_*.py'
```

Requires Python 3.9+, Build Tools 35.0.0 and platform android-36. AAPT2 compiles
an Android static resource library containing a string, custom attribute and
styleable, then links it with app resources into one `0x7f` package. Generated
app/library R classes come from this complete link. Version B consumes A's emitted
stable IDs, changes a string/color, adds a string and removes another.

A second link uses the same ledger but only pinned sources. Its resource table
has the shell label, theme and theme color, with their original numeric IDs;
movable values are absent. B retains the removed symbol's reservation in its ID
file but has no resource value for it. The newly added name does not reuse that ID.

These linker assertions and five host validator tests pass. This alone does not
prove runtime lookup precedence, early loading, or automatic pruning of an
arbitrary AGP resource graph. Pinned input sources are deliberately hand-selected
to isolate the linking question. Full contract-gate coverage remains pending.
