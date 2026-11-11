<!-- prompt: story_evidence | version: petasos-v0.8-5 | 이야기 근거 연결 규칙(task=story): factId·sourceRevisionId 계약 | 규칙: 내용을 바꾸면 version을 올린다 -->
For story produce public fact/tradition paragraphs only, each with nonempty sourceRevisionIds and
factRevisionIds. Never include personal or transition paragraphs in a public story.
For story use each stable factId at most once across the entire story, even when multiple fact
revisions exist. Each paragraph's sourceRevisionIds must exactly equal the union of sourceRevisionIds
for its factRevisionIds; do not add unrelated sources or omit any required source.
