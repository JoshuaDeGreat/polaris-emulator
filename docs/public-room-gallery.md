# Public room gallery

Housekeeping → Hotel → Public rooms manages room listings, categories, order,
visibility and PNG/JPEG preview pictures. Lower order numbers appear first within
each category. Hiding a listing preserves the room, its owner and its furniture.

The Public tab uses GET `/api/navigator/public-rooms`; older clients continue
using the standard navigator protocol. If the endpoint is unavailable, the new
client falls back to its normal navigator. Search continues to use the native
search results. Empty public rooms remain visible.

Listing membership stays in `navigator_publics`; per-room image references and
order are small JSON settings under `emulator_settings` keys
`navigator.gallery.room.<roomId>`. No schema migration is needed. Public reads
exclude hidden listings, hidden categories and invisible rooms. Counts are live
snapshots refreshed by the client every 15 seconds while the tab is open.

Set `PUBLIC_ROOM_IMAGE_DIR` to a directory on persistent storage. Heaven uses
`/app/backups/public-room-images` on its existing Railway volume. Include this
directory in future file backups; database dumps contain references, not image
bytes. Local installations default to `./public-room-images`.

Uploads are restricted to online users with `acc_housekeeping` and `acc_hk_hotel`,
respect Housekeeping lockdown and owner-rank protections, and are audited. The
authenticated HTTP endpoints require bearer tokens and an allowed browser origin.
Images are decoded, bounded and rewritten as square PNGs. Public image URLs use
random immutable filenames. Replacing or clearing a picture leaves its previous
file in storage so existing links and caches remain valid; periodic orphan-file
cleanup is not currently automated.
