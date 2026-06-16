import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { EmailTemplatesComponent } from './email-templates.component';
import { EmailTemplate, EmailTemplatesService, RenderedMessage, TemplateList } from './email-templates.service';

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

  function setup(list: TemplateList, overrides: Partial<EmailTemplatesService> = {}) {
    const service: Partial<EmailTemplatesService> = {
      list: () => of(list),
      edit: () => of(base),
      applyTone: () => of(base),
      reset: () => of(base),
      lock: () => of({ ...base, locked: true }),
      unlock: () => of(base),
      preview: () => of({ subject: 'Hi Dana Lee', bodyText: 'Hello Dana Lee', bodyHtml: 'Hello Dana Lee', missingFields: [] }),
      ...overrides
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [EmailTemplatesComponent],
      providers: [{ provide: EmailTemplatesService, useValue: service }]
    });
    const fixture = TestBed.createComponent(EmailTemplatesComponent);
    fixture.detectChanges();
    return fixture;
  }

  it('lists the message types', () => {
    const fixture = setup({ templates: [base] });
    expect(fixture.nativeElement.textContent).toContain('INVITATION');
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
