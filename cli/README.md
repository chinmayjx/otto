# otto

The command agents, people and scripts use for everything. A thin client: each command is one HTTP call to the relay or the hub.

```
otto browser open|snapshot|click|type|screenshot|tabs|eval
otto phone screenshot|ui|tap|type|key|launch|install|logcat
otto desktop screenshot
otto show <url>
otto artifact publish|list|share
otto notify <text>
otto clip <id> | list | rm
otto status
otto setup
```

Output is short text for a model; screenshots are saved as files and their path printed. The session comes from `OTTO_SESSION` (set by the adapter).
