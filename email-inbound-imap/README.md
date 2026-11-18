# email-inbound-imap

Receives email by polling an IMAP mailbox.  There's no webhook: call `inbound.onReceived.pull()` on a schedule (every 1-5 minutes is polite to most servers).

```
imaps://<username>:<password>@<host>[:<port>][/<folder>]     implicit TLS, port 993 by default
imap://<username>:<password>@<host>[:<port>][/<folder>]      STARTTLS required, port 143 by default
```

Reference `ImapEmailInboundService` before parsing settings.  Username and password are URL-decoded, so write `@` as `%40`.  The folder defaults to `INBOX`.

`pull()` returns unread messages and marks them read **before** you process them: if your handler fails, those messages are not returned again.

| Provider | Host | Notes |
|---|---|---|
| Gmail | `imap.gmail.com` | Enable IMAP in Gmail settings; use an app password (needs 2-step verification) |
| Outlook / Office 365 | `outlook.office365.com` | The account must allow basic-auth IMAP |
| Yahoo | `imap.mail.yahoo.com` | App password |
| iCloud | `imap.mail.me.com` | App-specific password |
| Fastmail | `imap.fastmail.com` | App password |

Folder names are provider-specific (Gmail's spam folder is `[Gmail]/Spam`).  Attachments are loaded into memory.

Guide: [docs/email.md#receive-email](../docs/email.md#receive-email).
