import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { of, throwError } from 'rxjs';
import { EmailTemplatesComponent } from './email-templates.component';
import { EmailTemplate, EmailTemplatesService, RenderedMessage, SendResult, TemplateList } from './email-templates.service';
import { attachToBody, axeViolations, detachFromBody } from '../../../testing/axe';

/**
 * F21 SC-011: the email-templates component renders the missing-field warning, disables editing of a
 * locked template for a non-Admin, and renders a preview with sample data. The server is the security
 * boundary; the route guard + the disabled control are defense-in-depth.
 */
describe('EmailTemplatesComponent', () => {
  const base: EmailTemplate = {
    messageType: 'INVITATION', stageKey: 'BASE', subject: 'Hi {{candidate_name}}',
    body: 'Hello {{candidate_name}}', locked: false, version: 0, source: 'OVERRIDE',
    permittedTokens: ['candidate_name', 'workspace_name']
  };
  let attachedEls: HTMLElement[] = [];

  function setup(list: TemplateList, overrides: Partial<EmailTemplatesService> = {}) {
    const service: Partial<EmailTemplatesService> = {
      list: () => of(list),
      edit: () => of(base),
      applyTone: () => of(base),
      reset: () => of(base),
      lock: () => of({ ...base, locked: true }),
      unlock: () => of(base),
      preview: () => of({ subject: 'Hi Dana Lee', bodyText: 'Hello Dana Lee', bodyHtml: 'Hello Dana Lee', missingFields: [] }),
      sendToCandidate: () => of({ dispatchId: 'd1', status: 'SENT', messageType: 'INVITATION' } as SendResult),
      ...overrides
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [EmailTemplatesComponent],
      providers: [{ provide: EmailTemplatesService, useValue: service }]
    });
    const fixture = TestBed.createComponent(EmailTemplatesComponent);
    const el = fixture.nativeElement as HTMLElement;
    attachedEls.push(el);
    attachToBody(el);
    fixture.detectChanges();
    return fixture;
  }

  afterEach(() => {
    attachedEls.forEach(detachFromBody);
    attachedEls = [];
  });

  it('lists the message types', () => {
    const fixture = setup({ templates: [base] });
    expect(fixture.nativeElement.textContent).toContain('INVITATION');
  });

  it('renders the shared page-header masthead', () => {
    const fixture = setup({ templates: [base] });
    expect(fixture.nativeElement.querySelector('app-page-header .page__head h1')).not.toBeNull();
  });

  it('shows the guided empty-state when there are no templates', () => {
    const fixture = setup({ templates: [] });
    expect(fixture.nativeElement.querySelector('app-empty-state')).not.toBeNull();
  });

  it('has zero axe WCAG 2.2 AA violations', async () => {
    const fixture = setup({ templates: [base] });
    const violations = await axeViolations(fixture.nativeElement);
    expect(violations).withContext(violations.map((v) => v.id).join(', ')).toEqual([]);
  });

  it('renders a preview with sample data', () => {
    const fixture = setup({ templates: [base] });
    fixture.componentInstance.preview(base);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Hi Dana Lee');
    expect(fixture.nativeElement.textContent).toContain('Hello Dana Lee');
  });

  it('shows a visible warning when a merge field is missing', () => {
    const rendered: RenderedMessage = {
      subject: 'Hi [[missing:candidate_name]]', bodyText: 'Hello [[missing:candidate_name]]',
      bodyHtml: 'Hello [[missing:candidate_name]]', missingFields: ['candidate_name']
    };
    const fixture = setup({ templates: [base] }, { preview: () => of(rendered) });
    fixture.componentInstance.preview(base);
    fixture.detectChanges();
    const alert = fixture.nativeElement.querySelector('[role="alert"]');
    expect(alert).not.toBeNull();
    expect(alert.textContent).toContain('candidate_name');
  });

  it('sends the previewed template to a candidate (happy path)', () => {
    const fixture = setup({ templates: [base] });
    fixture.componentInstance.preview(base);
    fixture.detectChanges();
    fixture.componentInstance.sendCandidateId = 'cand1';
    fixture.componentInstance.send(base);
    fixture.detectChanges();
    expect(fixture.componentInstance.sendStatus()).toBe('SENT');
    expect(fixture.componentInstance.sendError()).toBeNull();
    const status = fixture.nativeElement.querySelector('[role="status"]');
    expect(status).not.toBeNull();
    expect(status.textContent).toContain('SENT');
  });

  it('shows the not-contactable reason on a 409', () => {
    const err = new HttpErrorResponse({ status: 409, error: { error: 'not_contactable', reason: 'WITHDRAWN' } });
    const fixture = setup({ templates: [base] }, { sendToCandidate: () => throwError(() => err) });
    fixture.componentInstance.preview(base);
    fixture.detectChanges();
    fixture.componentInstance.sendCandidateId = 'cand1';
    fixture.componentInstance.send(base);
    fixture.detectChanges();
    expect(fixture.componentInstance.sendStatus()).toBeNull();
    const alert = Array.from(fixture.nativeElement.querySelectorAll('[role="alert"]'))
      .find((el) => /WITHDRAWN/.test((el as HTMLElement).textContent ?? ''));
    expect(alert).toBeTruthy();
  });

  it('disables the Edit control on a locked template for a non-Admin', () => {
    const locked: EmailTemplate = { ...base, locked: true };
    const fixture = setup({ templates: [locked] });
    fixture.componentInstance.isAdmin = false;
    fixture.detectChanges();
    const buttons: HTMLButtonElement[] = Array.from(fixture.nativeElement.querySelectorAll('button'));
    const editBtn = buttons.find((b) => /edit/i.test(b.textContent ?? ''));
    expect(editBtn).toBeTruthy();
    expect(editBtn!.disabled).toBeTrue();
    expect(fixture.componentInstance.canEdit(locked)).toBeFalse();
  });
});
