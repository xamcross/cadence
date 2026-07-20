import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { SideNavComponent } from './side-nav.component';
import { AuthService } from '../../core/auth/auth.service';
import { MemberSummary } from '../../core/auth/auth.models';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

const admin: MemberSummary = { memberId: 'm1', workspaceId: 'w1', role: 'ADMIN', displayName: 'Ada', email: 'a@x.co', workspaceConfigured: true };

describe('SideNavComponent', () => {
  let fixture: ComponentFixture<SideNavComponent>;
  let el: HTMLElement;
  let member$: BehaviorSubject<MemberSummary | null>;

  beforeEach(() => {
    member$ = new BehaviorSubject<MemberSummary | null>(admin);
    TestBed.configureTestingModule({
      imports: [SideNavComponent],
      providers: [provideRouter([]), { provide: AuthService, useValue: { member$ } }]
    });
    fixture = TestBed.createComponent(SideNavComponent);
    el = fixture.nativeElement as HTMLElement;
    attachToBody(el);
    fixture.detectChanges();
  });
  afterEach(() => detachFromBody(el));

  it('renders a Primary nav landmark with role-filtered links', () => {
    const nav = el.querySelector('nav[aria-label="Primary"]');
    expect(nav).not.toBeNull();
    const links = el.querySelectorAll('.side-nav__link');
    expect(links.length).toBeGreaterThan(5);
    // Admin sees the Members admin link:
    expect(Array.from(links).some((a) => a.getAttribute('href') === '/admin/members')).toBe(true);
  });

  it('hides admin-only links for an interviewer', () => {
    member$.next({ ...admin, role: 'INTERVIEWER' });
    fixture.detectChanges();
    const hrefs = Array.from(el.querySelectorAll('.side-nav__link')).map((a) => a.getAttribute('href'));
    expect(hrefs).not.toContain('/admin/members');
    expect(hrefs).toContain('/calendar/connections'); // interviewer keeps the personal link
  });

  it('toggles the mobile accordion open state', () => {
    const toggle = el.querySelector('.side-nav__toggle') as HTMLButtonElement;
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    toggle.click(); fixture.detectChanges();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(el.querySelector('.side-nav__panel--open')).not.toBeNull();
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    const v = await axeViolations(el);
    expect(v).withContext(v.map((x) => x.id).join(', ')).toEqual([]);
  });
});
