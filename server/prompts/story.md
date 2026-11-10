<!-- prompt: story | version: petasos-v0.8-5 | 이야기 생성(task=story) -->
For story produce public fact/tradition paragraphs only, each with nonempty sourceRevisionIds and
factRevisionIds. Never include personal or transition paragraphs in a public story.
For story use each stable factId at most once across the entire story, even when multiple fact
revisions exist. Each paragraph's sourceRevisionIds must exactly equal the union of sourceRevisionIds
for its factRevisionIds; do not add unrelated sources or omit any required source.
