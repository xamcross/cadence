import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { UpgradePromptComponent } from './upgrade-prompt.component';
import { AuthService } from '../../core/auth/auth.service';
import { MemberSummary } from '../../core/auth/auth.models';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

function memberWith(role: MemberSummary['role']): MemberSummary {
  return { memberId: 'm1', workspaceId: 'ws1', role, displayName: 'M', email: 'm@x.com', workspaceConfigured: true };
}

describe('UpgradePromptComponent (032 FR-016)', () => {
  let member$: BehaviorSubject<MemberSummary | null>;
  let fixture: ComponentFixture<UpgradePromptComponent>;
  let el: HTMLElement;

  function create(role: MemberSummary['role']): void {
    member$ = new BehaviorSubject<MemberSummary | null>(memberWith(role));
    TestBed.configureTestingModule({
      imports: [UpgradePromptComponent],
      providers: [provideRouter([]), { provide: AuthService, useValue: { member$: member$.asObservable() } }]
    });
    fixture = TestBed.createComponent(UpgradePromptComponent);
    fixture.componentRef.setInput('featureLabel', 'ATS integrations');
    el = fixture.nativeElement as HTMLElement;
    attachToBody(el);
    fixture.detectChanges();
  }

  afterEach(() => detachFromBody(el));

  it('admins get a link to the Billing page', () => {
    create('ADMIN');
    const link = el.querySelector('a[data-test=upgrade-link]') as HTMLAnchorElement;
    expect(link).toBeTruthy();
    expect(link.getAttribute('href')).toContain('/admin/billing');
  });

  it('non-admins get the contact-your-admin notice, no link', () => {
    create('RECRUITER');
    expect(el.querySelector('a[data-test=upgrade-link]')).toBeFalsy();
    expect(el.textContent).toContain('workspace admin');
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    create('ADMIN');
    const violations = await axeViolations(el);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });
});
