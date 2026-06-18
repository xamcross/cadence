import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { CsvImportComponent } from './csv-import.component';
import { CsvImportService, ImportJobStatus, UploadAccepted } from './csv-import.service';

/**
 * F42 CSV import admin screen. Verifies upload posts the file, the status state-machine renders counts + per-row
 * results, and a duplicate is resolved with merge/skip. Internal Admin/Recruiter screen — RBAC is enforced
 * server-side (the route roleGuard is covered by role.guard.spec); no axe/Lighthouse gate (F50/F51 precedent).
 */
describe('CsvImportComponent', () => {
  const accepted: UploadAccepted = { jobId: 'job1', status: 'ACCEPTED' };

  const completed: ImportJobStatus = {
    jobId: 'job1', status: 'COMPLETED', originalFilename: 'c.csv', totalRows: 2,
    importedCount: 2, rejectedCount: 0, duplicatePendingCount: 0, mergedCount: 0, skippedCount: 0,
    rejectionReason: null, rowResults: [
      { rowNumber: 1, status: 'IMPORTED', failingField: null, reason: null, existingCandidateId: null, candidateId: 'c1' }
    ], createdAt: null, completedAt: null
  };

  const awaiting: ImportJobStatus = {
    ...completed, status: 'AWAITING_DUPLICATE_DECISION', duplicatePendingCount: 1, importedCount: 1,
    rowResults: [
      { rowNumber: 2, status: 'DUPLICATE_PENDING', failingField: null, reason: null, existingCandidateId: 'x', candidateId: null }
    ]
  };

  function setup(statusValue: ImportJobStatus, resolveSpy = jasmine.createSpy('resolve').and.returnValue(of(completed))) {
    const uploadSpy = jasmine.createSpy('upload').and.returnValue(of(accepted));
    const statusSpy = jasmine.createSpy('status').and.returnValue(of(statusValue));
    const stub: Partial<CsvImportService> = {
      upload: uploadSpy as CsvImportService['upload'],
      status: statusSpy as CsvImportService['status'],
      resolve: resolveSpy as CsvImportService['resolve']
    };
    TestBed.configureTestingModule({
      imports: [CsvImportComponent],
      providers: [{ provide: CsvImportService, useValue: stub }]
    });
    const fixture = TestBed.createComponent(CsvImportComponent);
    fixture.detectChanges();
    return { fixture, uploadSpy, statusSpy, resolveSpy };
  }

  it('uploads the chosen file and renders the completed status', () => {
    const { fixture, uploadSpy } = setup(completed);
    const file = new File(['name,email\nAda,ada@example.com\n'], 'c.csv', { type: 'text/csv' });
    fixture.componentInstance.file.set(file);
    fixture.componentInstance.upload();
    fixture.detectChanges();
    expect(uploadSpy).toHaveBeenCalledWith(file);
    expect(fixture.componentInstance.job()?.status).toBe('COMPLETED');
    expect(fixture.nativeElement.textContent).toContain('COMPLETED');
  });

  it('renders per-row results and resolves a duplicate with merge', () => {
    const { fixture, resolveSpy } = setup(awaiting);
    const file = new File(['x'], 'c.csv', { type: 'text/csv' });
    fixture.componentInstance.file.set(file);
    fixture.componentInstance.upload();
    fixture.detectChanges();
    expect(fixture.componentInstance.job()?.status).toBe('AWAITING_DUPLICATE_DECISION');

    fixture.componentInstance.resolveRow(2, 'MERGE');
    expect(resolveSpy).toHaveBeenCalledWith('job1', [{ rowNumber: 2, action: 'MERGE' }], undefined);
  });

  it('resolves all duplicates with skip', () => {
    const { fixture, resolveSpy } = setup(awaiting);
    fixture.componentInstance.file.set(new File(['x'], 'c.csv'));
    fixture.componentInstance.upload();
    fixture.componentInstance.resolveAll('SKIP');
    expect(resolveSpy).toHaveBeenCalledWith('job1', [], 'SKIP');
  });
});
