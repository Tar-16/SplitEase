# Group Detail Add Expense UI — 26 October 2026

The floating + action is replaced with a full-width, rounded teal “+ Add Expense” button directly below Members / Expenses / Balances. It appears only for an authorized Group Detail Expenses tab, independently of whether expenses exist. Tabs/action remain outside the scrolling list; the summary scrolls away, initially collapsed in landscape to leave usable list space. Loading/error states hide group-specific tabs, summary and action until an authorized detail is available; errors expand the header. The existing Add Expense navigation receives the same group UUID.

Only GroupDetailFragment, its XML, a string resource, UI tests and documentation changed for this refinement. No repository, SQLite, financial, receipt, settlement or authentication logic changes.

Focused verification: app/test APK builds passed. Four API 37 emulator tests passed in 31.903s: the new action/scroll/tab/recreation/navigation check, existing expense create/edit/delete/account switching, and both existing group navigation/authorization tests. Screenshots reviewed in light/dark and landscape, using 864 × 1920 pixels at density 420 (approximately 329dp portrait width) and 1.3× text. Device size/font settings were restored. No full suite or lint rerun for this UI-only request.

After strengthening the measured-scroll assertion, that focused test passed separately (1/1, 11.438s). The scroll test additionally waits for the 15-expense list to be measured, scrolls to the bottom, checks a positive scroll offset and verifies the entire Add Expense button remains visible. Evidence screenshots are in [screenshots/group-detail-add-expense](screenshots/group-detail-add-expense). Physical-device and exhaustive accessibility coverage are not claimed.
