# Agent labels

Labels made by an agent (Claude) reviewing `zoom-eval review` renders of `CropConfig.TUNED`'s
crops against the owner's stated preferences (labels/ROUND-*.md), to catch failures on papers the
owner hasn't labelled. Same schema as the owner's files, with `"labeller": "agent"`. They never
override the owner's labels, which live one directory up, and the fit does not read them unless
asked (`fit --labels=labels/agent`). Treat them as weaker evidence: an agent judging a 360 px
render, not the owner on the tablet.
