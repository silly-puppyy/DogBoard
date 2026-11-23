#!/bin/bash
# next training / test text (2026-11-15: "put the big datasets to download now"); each in its own dir
D=/run/media/syn/a1991982-8703-474a-926a-020824310f2e/keyboard-data
P=$D/venv-llm/bin/python
dl() { HF_HUB_DISABLE_PROGRESS_BARS=1 $P -c "from huggingface_hub import snapshot_download as s; s('$1', repo_type='dataset', local_dir='$2', allow_patterns=$3, max_workers=4)" && echo "done $1 $(du -sh $2 | cut -f1)"; }
mkdir -p $D/corpora/enron && (cd $D/corpora/enron && curl -s -L -o enron_mail_20150507.tar.gz https://www.cs.cmu.edu/~enron/enron_mail_20150507.tar.gz && echo "done enron $(du -sh . | cut -f1)") &
dl allenai/soda $D/corpora/soda "['*.parquet','README.md']" &
dl webis/tldr-17 $D/corpora/reddit_tldr17 "['data/*','README.md']" &
wait
dl HuggingFaceFW/fineweb $D/fineweb/sample-10BT "['sample/10BT/*']"
echo "all downloads done; SSD free $(df -h $D | awk 'NR==2{print $4}')"
