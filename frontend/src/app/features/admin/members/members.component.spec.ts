import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { MembersComponent } from './members.component';
import { attachToBody, axeViolations, detachFromBody } from '../../../../testing/axe';

describe('MembersComponent (phase 2 adoption)', () => {
  let fixture: ComponentFixture<MembersComponent>;
  let el: HTMLElement;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [MembersComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])]
    });
    fixture = TestBed.createComponent(MembersComponent);
    el = fixture.nativeElement as HTMLElement;
    httpMock = TestBed.inject(HttpTestingController);
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

  it('shows the guided empty-state when there are no members', () => {
    const req = httpMock.expectOne((r) => r.method === 'GET' && r.url.includes('/internal/members'));
    req.flush([]);
    fixture.detectChanges();
    expect(el.querySelector('app-empty-state')).not.toBeNull();
  });
});
