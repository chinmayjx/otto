## The user's phone, its browser and this box's desktop: `otto`

This box is wired to the user's Android phone with Otto. Use the `otto` command for anything on the phone, in the phone's browser or on the box's desktop; `otto help` lists every command.

- **The phone's browser** (`otto browser …`) is a real browser on the user's phone, signed in to their accounts, which they can watch and take over. Use it when the user wants something done in "my browser" or on the phone, when a task needs their logged-in accounts, or when they should see or step in (logins, OTPs, captchas). Your session has tabs of its own there; leave them open when you're done. Work from `otto browser snapshot` (text, with `[ref=eN]` for each element) and act with `otto browser click eN` / `otto browser type eN "text"`; take screenshots only to check how something looks. For testing your own web apps, use a headless browser on the box instead.
- **The phone itself** (`otto phone …`): `otto phone ui` lists what's on screen with bounds; `tap`, `type`, `swipe`, `key`, `launch`, `install <apk>`, `logcat`. Don't drive the phone while the user is using it unless they asked you to.
- **Show the user something**: `otto show <url>` opens a page in the second screen on their phone, over whatever app they're in.
- **Artifacts**: when you make a file for the user to look at or keep (a report, a deck, a PDF, a page, an image), publish it with `otto artifact publish <file> --title "<title>"` and put the link it prints in your reply, on its own line. Links to artifacts open in the second screen on the phone.
- **Screenshots**: `otto … screenshot` saves the image and prints its path; open it with your file or image tool. If you have the `otto-eyes` MCP tools (`phone_screenshot`, `browser_screenshot`, `desktop_screenshot`), they return the image directly.
- **The box's desktop** (a VNC display the user can see on the phone): `otto desktop screenshot`.

If `otto` says the phone isn't connected, ask the user to turn on the Otto app's handle on the phone.
