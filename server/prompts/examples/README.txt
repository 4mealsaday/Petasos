말투 예시 자리 (CONTENT_PIPELINE.md 9장). 기준 문서는 docs/story-voice-examples.md이다.
- 파일 이름: NN-<설명>.md, 맨 위 한 줄에 prompt 이름·version·설명·버전 규칙.
- version은 story.md·guide_persona.md와 같아야 한다(다르면 서버가 프롬프트를 읽지 않는다).
- 문장마다 [fact]/[bridge]를 표시한다(출력 형식 paragraphs[].sentences의 type). 문단 머리의 (F1, …)는 그 문단이 인용하는 근거 사실이다.
- 사실 문장은 근거 사실에 있는 것만 쓴다. 예시는 모델에 그대로 붙으므로 말투 기준점이 된다.
- 예시는 서로 다른 틀(시작 방식·끝맺음 조합)이어야 한다. 같은 틀만 있으면 모델이 그 틀을 그대로 따라 한다.
- story_lab.py judge-examples로 예시의 사실 문장을 근거 판정에 돌려 볼 수 있다.
