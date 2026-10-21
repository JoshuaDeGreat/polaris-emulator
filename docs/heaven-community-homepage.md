# Heaven community website integration

New additive Polaris endpoints leave existing clients and API payloads unchanged:

- `GET /api/community/status`: online users, open room count, maintenance. A ten-second server cache avoids repeated room-count queries.
- `GET /api/community/news`: published `ui_news`, sorted by display order then ID descending.
- `GET /api/community/rooms`: existing public room gallery, including occupancy and stored images.
- `GET /community/staff`: standalone staff news editor served from the emulator resource bundle.
- `GET/POST /api/community/staff/news`: authenticated editor list/save, requiring an online user with Housekeeping hotel access and existing lockdown/top-rank policy. POST additionally requires exact HTTPS origin/Host match. Public feeds allow cross-origin GET only; admin APIs do not allow cross-origin access.

Staff workflow: keep the hotel open and signed in, open the staff news desk from the homepage footer, sign in with the same hotel account, create/edit and preview a story, and save it as a draft or published. Uncheck Published to hide it. Existing login news and the homepage share the same table. Editor tokens live in memory only and expire under the existing access-token policy. Closing the editor session clears the editor state without logging the player out of the hotel.

No database migration is needed. News saves use parameterised SQL, input limits, HTTPS-only image/link URLs and the existing audit log. Public output contains no operational metrics, authentication data or staff-only drafts. The current homepage client-entry action opens Octane; room-specific deep links and a shared homepage/player session remain separate future work.
