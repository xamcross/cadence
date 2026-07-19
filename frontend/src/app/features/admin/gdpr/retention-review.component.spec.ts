import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { RetentionReviewComponent } from './retention-review.component';
import { attachToBody, axeViolations, detachFromBody } from '../../../../testing/axe';

describe('RetentionReviewComponent (phase 2 adoption)', () => {
  let fixture: ComponentFixture<RetentionReviewComponent>;
  let el: HTMLElement;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [RetentionReviewComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
    });
    fixture = TestBed.createComponent(RetentionReviewComponent);
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

  it('shows the guided empty-state when there are no flagged records', () => {
    const httpMock = TestBed.inject(HttpTestingController);
    const req = httpMock.expectOne((r) => r.method === 'GET' && r.url.includes('/retention/flagged'));
    req.flush({ flagged: [] });
    fixture.detectChanges();
    expect(el.querySelector('app-empty-state')).not.toBeNull();
  });
});
