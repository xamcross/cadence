import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { CandidateAuditComponent } from './candidate-audit.component';
import { attachToBody, axeViolations, detachFromBody } from '../../../../testing/axe';

describe('CandidateAuditComponent (phase 2 adoption)', () => {
  let fixture: ComponentFixture<CandidateAuditComponent>;
  let el: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [CandidateAuditComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
    });
    fixture = TestBed.createComponent(CandidateAuditComponent);
    el = fixture.nativeElement as HTMLElement;
    attachToBody(el);
    fixture.detectChanges();
  });

  afterEach(() => detachFromBody(el));

  it('renders the shared page-header masthead', () => {
    expect(el.querySelector('app-page-header .page__head h1')).not.toBeNull();
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    const violations = await axeViolations(el);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });

  it('shows the guided empty-state when the audit log has no entries', () => {
    fixture.componentInstance.candidateId = 'c1';
    fixture.componentInstance.load();
    fixture.detectChanges();
    const httpMock = TestBed.inject(HttpTestingController);
    const req = httpMock.expectOne((r) => r.method === 'GET' && r.url.includes('/audit'));
    req.flush({ entries: [] });
    fixture.detectChanges();
    expect(el.querySelector('app-empty-state')).not.toBeNull();
  });
});
