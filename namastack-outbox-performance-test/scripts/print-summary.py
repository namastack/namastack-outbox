#!/usr/bin/env python3
"""Print committed production counts and fail a finished run that did not establish capacity."""
import json
import sys
from pathlib import Path


def production_summary(data):
    seconds = data['seconds']
    produced = data['produced']
    counts = data['counts']
    completed = counts['completedRecords']
    pending = counts['newRecords']
    failed = counts['failedRecords']
    if seconds <= 0 or min(produced, completed, pending, failed) < 0:
        raise ValueError('Invalid production counts or duration')
    if completed + pending + failed != produced:
        raise ValueError('Production counts do not reconcile')
    return '\n'.join([
        '| Metric | Result |', '| --- | ---: |',
        f'| Production time | {seconds:,.2f} s |',
        f'| Produced | {produced:,} |',
        f'| Completed at producer stop | {completed:,} |',
        f'| Pending at producer stop | {pending:,} |',
        f'| Average processing during production | {completed / seconds:,.2f} records/s |',
    ])


def validate_result(parameters, ordering):
    if parameters['status'] != 'VALID':
        raise ValueError('Final delivery counts are invalid')
    if (not ordering['valid'] or ordering['violations'] != 0 or ordering['missingCompletions'] != 0
            or ordering['checkedRecords'] != parameters['expectedRecords']):
        raise ValueError('Full ordering audit did not pass')
    stats = parameters.get('steadyState')
    if stats is not None and not (stats['producerRateCompliant'] and stats['sustainable']):
        raise ValueError('Producer target missed or backlog unsustainable; inspect report.md')


def main():
    source = Path(sys.argv[1])
    if source.is_file():
        print(production_summary(json.loads(source.read_text())))
        return
    parameters = json.loads((source / 'parameters.json').read_text())
    ordering = json.loads((source / 'completion-order.json').read_text())
    validate_result(parameters, ordering)
    text = 'Full ordering audit passed.\n'
    production = source / 'production-summary.json'
    if production.exists():
        text = production_summary(json.loads(production.read_text())) + '\n\n' + text
    (source / 'summary.md').write_text(text)
    print(text)


if __name__ == '__main__':
    main()
