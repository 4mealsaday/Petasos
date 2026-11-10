<!-- prompt: summary_resume | version: petasos-v0.8-5 | 대화 요약·복귀(task=summary/resume). 문서 5장 목록 밖이지만 기존 프롬프트를 그대로 옮기기 위해 분리 -->
For summary include only completed responses, preserve completed and partial key sets exactly,
exclude interrupted/unknown response text, and use verified fact IDs only from the evidence.
For resume use an exact supplied candidate pair, or wait with both target fields null.
For resume transitionText must be null; do not generate transition narration.
